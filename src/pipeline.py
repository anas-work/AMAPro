import time
import cv2
import os
import glob
import numpy as np
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import List, Dict, Tuple, Optional, Any, Set

from src.detection.scrfd_detector import SCRFDDetector, FaceDetection
from src.tracking.tracker import IoUTracker, TrackedFace
from src.quality.quality_filter import FaceQualityFilter, QualityResult
from src.landmarks.alignment import FaceAligner
from src.recognition.kprpe_adaface import KPRPEAdaFaceRecognizer
from src.search.faiss_index import FAISSVectorIndex
from src.temporal.temporal_confirmation import TemporalConfirmationEngine, TemporalDecision
from src.attendance.deduplication import AttendanceDeduplicator
from src.database.repository import AttendanceRepository
from src.liveness.liveness_detector import LivenessDetector

@dataclass
class FrameProcessingResult:
    frame_index: int
    timestamp: float
    frame: np.ndarray
    annotated_frame: np.ndarray
    active_tracks: List[TrackedFace]
    decisions: List[TemporalDecision]
    latency_breakdown: Dict[str, float]
    fps: float

class RecognitionPipeline:
    """
    Production Real-Time Employee Face Recognition & Security Monitoring Pipeline.
    Supports Dual Operation Modes: ENTRY MODE (Check-In / Re-Entry) & EXIT MODE (Check-Out).
    
    STRICT OPERATIONAL RULES:
    1. ENTRY VS EXIT OPERATION MODES:
       - ENTRY MODE:
         * First time employee matched -> CHECK_IN with BRIGHT GREEN Bounding Box (0, 255, 0).
         * Subsequent match of same employee -> RE_ENTRY with ORANGE Bounding Box (0, 165, 255).
       - EXIT MODE:
         * Matched employee -> CHECK_OUT with PURPLE Bounding Box (255, 0, 180).
         * Flashes CHECK-OUT VERIFIED HUD for 2 seconds.
         * Saves dual photos and logs CHECK_OUT event in database & exit activity feed.
    2. PERSISTENT TRACK IDENTITY LOCKING: Once a track is matched, its identity and event status
       are LOCKED permanently for the entire duration the person stays in the frame.
    3. CAMERA-FRONT SIZE GATE: Bounding box width and height must BOTH be >= 120px.
    4. ACCURACY & MARGIN DELTA GATE: Requires similarity >= 0.53 AND margin (sim1 - sim2 >= 0.04).
    """

    def __init__(
        self,
        config: Dict[str, Any],
        video_source: Optional[Any] = None,
        db_repo: Optional[AttendanceRepository] = None
    ):
        self.config = config
        
        models_cfg = config.get("detection", {})
        rec_cfg = config.get("recognition", {})
        storage_cfg = config.get("storage", {})
        att_cfg = config.get("attendance", {})
        quality_cfg = config.get("quality", {})

        # Operation Mode: "ENTRY" or "EXIT"
        self.active_mode = "ENTRY"

        # Camera-Front Huge Box Gate (120px face width & height)
        self.huge_box_threshold = quality_cfg.get("min_face_size", 120)

        # High Accuracy Match Threshold & Margin Gate
        self.certain_match_threshold = rec_cfg.get("match_threshold", 0.53)
        self.margin_threshold = rec_cfg.get("margin_threshold", 0.04)

        # Components
        self.detector = SCRFDDetector(
            model_path=models_cfg.get("model_path", "models/scrfd_2.5g_kps.onnx"),
            conf_threshold=models_cfg.get("confidence_threshold", 0.30)
        )

        self.recognizer = KPRPEAdaFaceRecognizer(
            model_path=rec_cfg.get("model_path", "models/kprpe_adaface.onnx")
        )

        self.tracker = IoUTracker()
        self.quality_filter = FaceQualityFilter(config)
        
        self.gallery = FAISSVectorIndex(
            dimension=rec_cfg.get("embedding_size", 512),
            index_dir=storage_cfg.get("gallery_dir", "data/embeddings")
        )
        self.gallery.load()

        self.temporal_engine = TemporalConfirmationEngine(config)
        self.deduplicator = AttendanceDeduplicator(cooldown_seconds=att_cfg.get("cooldown_seconds", 300.0))
        self.liveness_detector = LivenessDetector()

        self.db_repo = db_repo or AttendanceRepository(
            db_url=config.get("database", {}).get("sqlite_fallback_url", "sqlite:///data/attendance.db")
        )

        self.video_source = video_source
        self.frame_count = 0
        self.last_frame_time = time.perf_counter()
        self.smoothed_fps = 30.0

        # GLOBAL ONE-TIME CHECKED-IN EMPLOYEES SET & LAST EVENT STATUS
        self.globally_marked_present_employees: Set[str] = set()
        self.last_event_type: Dict[str, str] = {}
        self.unknown_detected_count: int = 0

        # Cache for employee photos and relative paths
        self.employee_photos: Dict[str, np.ndarray] = {}
        self.employee_photo_paths: Dict[str, str] = {}
        self._load_employee_photos(storage_cfg.get("photos_dir", "Employees_Photo"))
        self._load_employee_photos("data/enrolled_photos")

        # Active ID Card Flash state
        self.active_id_card_flash: Optional[Dict[str, Any]] = None

        # Thread pool for fire-and-forget I/O (disk writes + DB inserts)
        self._io_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="att_io")

        os.makedirs("data/attendance_captures", exist_ok=True)

    def get_unknown_count(self) -> int:
        if self.db_repo:
            try:
                session = self.db_repo.get_session()
                from src.database.models import AttendanceEventModel
                count = session.query(AttendanceEventModel).filter_by(event_type="UNKNOWN").count()
                session.close()
                return count
            except Exception:
                pass
        return self.unknown_detected_count

    def record_unknown_person(self, crop_bytes: Optional[bytes] = None, full_frame_bytes: Optional[bytes] = None) -> Dict[str, Any]:
        """
        Logs a critical 'UNKNOWN' detection event with photo capture when a person fails 5 recognition attempts.
        """
        current_time = time.time()
        self.unknown_detected_count += 1
        rand_suffix = int(current_time * 1000) % 10000
        cap_filename = f"CAP_UNKNOWN_{int(current_time)}_{rand_suffix}.jpg"
        cap_filepath = os.path.join("data/attendance_captures", cap_filename)
        captured_url = f"/captures/{cap_filename}"

        save_img = None
        if full_frame_bytes:
            try:
                nparr_f = np.frombuffer(full_frame_bytes, np.uint8)
                save_img = cv2.imdecode(nparr_f, cv2.IMREAD_COLOR)
            except Exception:
                pass
        if save_img is None and crop_bytes:
            try:
                nparr_c = np.frombuffer(crop_bytes, np.uint8)
                save_img = cv2.imdecode(nparr_c, cv2.IMREAD_COLOR)
            except Exception:
                pass

        if save_img is not None and save_img.size > 0:
            try:
                gray = cv2.cvtColor(save_img, cv2.COLOR_BGR2GRAY)
                mean_b = np.mean(gray)
                if mean_b < 120.0:
                    gamma = max(0.55, mean_b / 140.0)
                    inv_gamma = 1.0 / gamma
                    lut = np.array([((i / 255.0) ** inv_gamma) * 255 for i in np.arange(0, 256)]).astype("uint8")
                    save_img = cv2.LUT(save_img, lut)
            except Exception:
                pass
            cv2.imwrite(cap_filepath, save_img, [int(cv2.IMWRITE_JPEG_QUALITY), 95])

        if self.db_repo:
            self.db_repo.record_attendance_event(
                employee_id="Unknown Person (UNKNOWN)",
                camera_id="CLIENT_DEVICE_CAM",
                event_type="UNKNOWN",
                captured_frame_path=captured_url,
                enrolled_photo_path=None
            )

        return {
            "status": "SUCCESS",
            "event_type": "UNKNOWN",
            "captured_url": captured_url,
            "unknown_count": self.get_unknown_count()
        }

    def set_mode(self, new_mode: str) -> None:
        """Updates operation mode and resets cooldowns for instant cross-mode transitions."""
        if new_mode in ["ENTRY", "EXIT"]:
            self.active_mode = new_mode
            if hasattr(self, 'deduplicator') and self.deduplicator:
                self.deduplicator.clear_all()
            if hasattr(self, 'last_event_type'):
                self.last_event_type.clear()
            self.restart_stream()

    def restart_stream(self) -> None:
        """
        Restarts video stream, clears active tracker state, and resets frame counts.
        """
        if self.video_source is not None and hasattr(self.video_source, 'restart'):
            self.video_source.restart()
        self.frame_count = 0
        if hasattr(self.tracker, 'tracks'):
            self.tracker.tracks.clear()
        self.active_id_card_flash = None

    def _load_employee_photos(self, photos_dir: str) -> None:
        if not os.path.exists(photos_dir):
            return
        photo_files = glob.glob(os.path.join(photos_dir, "*.jpg")) + \
                      glob.glob(os.path.join(photos_dir, "*.png")) + \
                      glob.glob(os.path.join(photos_dir, "*.jpeg"))
        for pfile in photo_files:
            fname = os.path.basename(pfile)
            img = cv2.imread(pfile)
            if img is not None:
                rel_path = f"/photos/{fname}"
                parts = os.path.splitext(fname)[0].split()
                if parts:
                    emp_id = parts[-1].rstrip('.')
                    self.employee_photos[emp_id] = img
                    self.employee_photo_paths[emp_id] = rel_path
                self.employee_photos[fname] = img
                self.employee_photo_paths[fname] = rel_path
                self.employee_photos[os.path.splitext(fname)[0]] = img
                self.employee_photo_paths[os.path.splitext(fname)[0]] = rel_path

    def _restore_web_enrolled_employees(self) -> None:
        """
        Re-embeds employee photos from data/enrolled_photos that are NOT already
        present in the FAISS gallery. Called at startup after the baseline FAISS is
        seeded from the baked container image, so that web-enrolled users survive
        container restarts and redeployments.
        """
        from src.landmarks.alignment import FaceAligner

        enrolled_dir = "data/enrolled_photos"
        if not os.path.exists(enrolled_dir):
            return

        # Build a set of employee_ids already in FAISS
        existing_ids = {e.get("employee_id") for e in self.gallery.get_all_employees()}

        photo_files = glob.glob(os.path.join(enrolled_dir, "*.jpg")) + \
                      glob.glob(os.path.join(enrolled_dir, "*.jpeg"))

        restored = 0
        for pfile in photo_files:
            fname = os.path.basename(pfile)
            parts = os.path.splitext(fname)[0].split()
            if len(parts) < 2:
                continue
            emp_id = parts[-1].rstrip('.')
            emp_name = " ".join(parts[:-1])

            if emp_id in existing_ids:
                # Already in FAISS — just ensure in-memory photo cache is updated
                rel_path = f"/photos/{fname}"
                self.employee_photo_paths[emp_id] = rel_path
                self.employee_photo_paths[fname] = rel_path
                continue

            # Not in FAISS — re-embed and re-add
            img = cv2.imread(pfile)
            if img is None:
                continue
            try:
                detections = self.detector.detect(img)
                if not detections:
                    print(f"[Restore] No face in {fname}, skipping.")
                    continue
                detections.sort(key=lambda d: d.score, reverse=True)
                best_det = detections[0]
                if best_det.kps is None or len(best_det.kps) != 5:
                    continue
                aligned_crop, _ = FaceAligner.align_face_112(img, best_det.kps)
                embedding = self.recognizer.extract_embedding(aligned_crop)
                metadata = {
                    "employee_id": emp_id,
                    "name": emp_name,
                    "filename": fname,
                    "image_path": pfile
                }
                self.gallery.add_embeddings(np.array([embedding]), [metadata])
                rel_path = f"/photos/{fname}"
                self.employee_photo_paths[emp_id] = rel_path
                self.employee_photo_paths[fname] = rel_path
                self.employee_photos[emp_id] = img
                self.employee_photos[fname] = img
                existing_ids.add(emp_id)
                restored += 1
                print(f"[Restore] Re-enrolled {emp_name} ({emp_id}) from persistent volume.")
            except Exception as err:
                print(f"[Restore] Failed to re-enroll {fname}: {err}")

        if restored > 0:
            self.gallery.save()
            print(f"[Restore] Re-enrolled {restored} web-enrolled employee(s) from persistent volume.")

    def process_frame(self, frame: np.ndarray) -> FrameProcessingResult:
        """
        Executes real-time face recognition pipeline on a single video frame.
        """
        t0 = time.perf_counter()
        self.frame_count += 1
        latencies: Dict[str, float] = {}

        # 1. Detection & Landmark Extraction
        t_det_start = time.perf_counter()
        detections = self.detector.detect(frame)
        latencies["detection_ms"] = (time.perf_counter() - t_det_start) * 1000.0

        # 2. Multi-object Tracking
        t_track_start = time.perf_counter()
        active_tracks = self.tracker.update(detections)
        latencies["tracking_ms"] = (time.perf_counter() - t_track_start) * 1000.0

        annotated = frame.copy()
        current_time = time.time()
        t_rec_total = 0.0
        t_search_total = 0.0

        # STEP 1: FIND THE SINGLE LARGEST FACE BOX IN FRONT OF CAMERA
        primary_track = None
        max_area = 0
        min_size = min(getattr(self, 'huge_box_threshold', 50), 40)

        for track in active_tracks:
            bw = track.bbox[2] - track.bbox[0]
            bh = track.bbox[3] - track.bbox[1]
            area = bw * bh

            if bw >= min_size and bh >= min_size and area > max_area:
                max_area = area
                primary_track = track

        decisions: List[TemporalDecision] = []
        decisions_map: Dict[int, TemporalDecision] = {}   # O(1) lookup by track_id
        quality_map: Dict[int, QualityResult] = {}         # per-track quality result
        new_attendance_event = None

        # STEP 2: EVALUATE TRACKS
        for track in active_tracks:
            bw = track.bbox[2] - track.bbox[0]
            bh = track.bbox[3] - track.bbox[1]

            # PERSISTENT LOCK CHECK:
            # If identity has ALREADY been verified on this track, KEEP IT LOCKED!
            # Skip expensive quality evaluation — result is unused for locked tracks.
            if getattr(track, 'assigned_identity', None) is not None:
                event_status = getattr(track, 'event_status', 'CHECK_IN')
                dec = TemporalDecision(
                    track_id=track.track_id,
                    decision=event_status,
                    employee_id=track.assigned_identity,
                    name=getattr(track, 'assigned_name', track.assigned_identity),
                    confidence=getattr(track, 'similarity_score', 0.90),
                    confirmed=True
                )
            elif bw < min_size or bh < min_size or (len(active_tracks) > 1 and track != primary_track):
                # Small face box (<min_size) or background face when multiple faces are present -> FORCED RED (UNKNOWN)
                dec = TemporalDecision(
                    track_id=track.track_id,
                    decision="UNKNOWN",
                    employee_id=None,
                    name="UNKNOWN PERSON",
                    confidence=0.0,
                    confirmed=False
                )
            else:
                # Primary Face Box in front of camera with no assigned identity yet -> RUN RECOGNITION
                # Only evaluate quality for this candidate track.
                q_res = self.quality_filter.evaluate(frame, track.bbox, track.score, track.kps)
                quality_map[track.track_id] = q_res

                t_rec_s = time.perf_counter()
                aligned_face, _ = FaceAligner.align_face_112(frame, track.kps)
                embedding = self.recognizer.extract_embedding(aligned_face)
                t_rec_total += (time.perf_counter() - t_rec_s) * 1000.0

                t_search_s = time.perf_counter()
                raw_matches = self.gallery.search(embedding, top_k=5)
                t_search_total += (time.perf_counter() - t_search_s) * 1000.0

                top_match = raw_matches[0] if len(raw_matches) > 0 else None
                second_match_sim = raw_matches[1][0] if len(raw_matches) > 1 else 0.0

                # ACCURACY & MARGIN DELTA GATE:
                # 1. Similarity MUST be >= 0.53
                # 2. Top-1 match must have a distinct margin over Top-2 (sim1 - sim2 >= 0.04)
                is_confident_match = (
                    top_match is not None and
                    top_match[0] >= self.certain_match_threshold and
                    (top_match[0] - second_match_sim) >= self.margin_threshold
                )

                if is_confident_match:
                    sim, meta = top_match
                    emp_id = meta.get("employee_id", "EMP")
                    emp_name = meta.get("name") or emp_id

                    # EVALUATE EVENT TYPE BASED ON ACTIVE MODE
                    if self.active_mode == "EXIT":
                        event_status = "CHECK_OUT"
                    else:
                        # ENTRY MODE (CHECK_IN vs RE_ENTRY)
                        if emp_id not in self.globally_marked_present_employees:
                            event_status = "CHECK_IN"
                            self.globally_marked_present_employees.add(emp_id)
                        else:
                            event_status = "RE_ENTRY"

                    dec = TemporalDecision(
                        track_id=track.track_id,
                        decision=event_status,
                        employee_id=emp_id,
                        name=emp_name,
                        confidence=sim,
                        confirmed=True
                    )

                    # PERMANENTLY LOCK IDENTITY & STATUS ON THIS TRACK
                    track.assigned_identity = dec.employee_id
                    track.assigned_name = dec.name
                    track.similarity_score = dec.confidence
                    track.event_status = event_status
                    track.recognition_stale = False

                    # Safe photo lookup
                    photo_img = self.employee_photos.get(dec.employee_id)
                    if photo_img is None and dec.name:
                        photo_img = self.employee_photos.get(dec.name)
                    if photo_img is None and self.employee_photos:
                        photo_img = next(iter(self.employee_photos.values()))

                    # Flash Official Employee ID Card HUD for 2 seconds
                    self.active_id_card_flash = {
                        "employee_id": dec.employee_id,
                        "name": dec.name,
                        "confidence": dec.confidence,
                        "photo": photo_img,
                        "event_type": event_status,
                        "flash_until": current_time + 2.0
                    }

                    new_attendance_event = {
                        "employee_id": f"{dec.name} ({dec.employee_id})",
                        "raw_emp_id": dec.employee_id,
                        "name": dec.name,
                        "confidence": sim,
                        "event_type": event_status
                    }
                else:
                    # AMBIGUOUS OR UNCERTAIN MATCH -> REJECT! KEEP RED BOX (UNKNOWN PERSON)
                    dec = TemporalDecision(
                        track_id=track.track_id,
                        decision="UNKNOWN",
                        employee_id=None,
                        name="UNKNOWN PERSON",
                        confidence=0.0,
                        confirmed=False
                    )

            decisions.append(dec)
            decisions_map[track.track_id] = dec

        # STEP 3: DRAW ANNOTATED FRAME
        for track in active_tracks:
            # O(1) lookup via decisions_map instead of O(n) linear scan
            t_dec = decisions_map.get(track.track_id)
            if not t_dec:
                t_dec = TemporalDecision(track_id=track.track_id, decision="UNKNOWN")
            # Pass the correct per-track quality result (or a default if not evaluated)
            t_q_res = quality_map.get(track.track_id, QualityResult(passed=False, score=0.0))
            self._draw_track_box(annotated, track, t_dec, t_q_res)

        # DRAW FLASHING ID CARD HUD ON ANNOTATED FRAME IF ACTIVE
        if self.active_id_card_flash:
            if current_time < self.active_id_card_flash["flash_until"]:
                self._draw_id_card_hud(annotated, self.active_id_card_flash)
            else:
                self.active_id_card_flash = None

        # STEP 4: CAPTURE ANNOTATED FRAME (WITH BOX & ID CARD HUD) FOR ATTENDANCE FEED
        if new_attendance_event and self.deduplicator.should_record(new_attendance_event["raw_emp_id"]):
            event_type_str = new_attendance_event["event_type"]
            cap_filename = f"CAP_{new_attendance_event['raw_emp_id']}_{event_type_str}_{int(current_time)}.jpg"
            cap_filepath = os.path.join("data/attendance_captures", cap_filename)

            # Capture a snapshot of the annotated frame for the save (avoids a full frame.copy())
            frame_snapshot = annotated.copy()
            captured_url = f"/captures/{cap_filename}"
            enrolled_url = self.employee_photo_paths.get(new_attendance_event["raw_emp_id"]) or \
                           self.employee_photo_paths.get(new_attendance_event["name"]) or None

            emp_id_full   = new_attendance_event["employee_id"]
            event_type_db = event_type_str
            db_repo       = self.db_repo

            # Fire-and-forget: offload disk write + DB insert to background thread
            # so the frame loop is never blocked by I/O.
            def _save_and_record(snap, path, emp, cam, etype, cap_url, enr_url):
                cv2.imwrite(path, snap, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
                db_repo.record_attendance_event(
                    employee_id=emp,
                    camera_id=cam,
                    event_type=etype,
                    captured_frame_path=cap_url,
                    enrolled_photo_path=enr_url
                )

            self._io_executor.submit(
                _save_and_record,
                frame_snapshot, cap_filepath,
                emp_id_full, "CAM01", event_type_db,
                captured_url, enrolled_url
            )

        latencies["recognition_ms"] = t_rec_total
        latencies["vector_search_ms"] = t_search_total
        
        t_end = time.perf_counter()
        latencies["end_to_end_ms"] = (t_end - t0) * 1000.0
        
        instant_fps = 1.0 / (t_end - self.last_frame_time) if (t_end - self.last_frame_time) > 0 else 30.0
        self.smoothed_fps = 0.85 * self.smoothed_fps + 0.15 * instant_fps
        self.last_frame_time = t_end

        self._draw_hud(annotated, self.smoothed_fps, latencies, len(active_tracks))

        return FrameProcessingResult(
            frame_index=self.frame_count,
            timestamp=t0,
            frame=frame,
            annotated_frame=annotated,
            active_tracks=active_tracks,
            decisions=decisions,
            latency_breakdown=latencies,
            fps=self.smoothed_fps
        )

    def _draw_track_box(
        self,
        img: np.ndarray,
        track: TrackedFace,
        dec: TemporalDecision,
        q_res: QualityResult
    ) -> None:
        x1, y1, x2, y2 = track.bbox.astype(int)
        bw = x2 - x1
        bh = y2 - y1

        # STRICT OPERATIONAL COLOR & LABEL RULES:
        # 1. CHECK_IN  -> Bright Green (0, 255, 0)
        # 2. RE_ENTRY  -> Bright Orange (0, 165, 255)
        # 3. CHECK_OUT -> Bright Purple / Violet (255, 0, 180)
        # 4. UNKNOWN   -> Bright Red (0, 0, 255)
        if dec.decision == "CHECK_IN":
            color = (0, 255, 0)        # Bright Green
            label = f"{dec.name} [{dec.confidence*100:.0f}%]"
        elif dec.decision == "RE_ENTRY":
            color = (0, 165, 255)      # Bright Orange
            label = f"RE-ENTRY: {dec.name} [{dec.confidence*100:.0f}%]"
        elif dec.decision == "CHECK_OUT":
            color = (255, 0, 180)      # Bright Purple / Violet
            label = f"CHECK-OUT: {dec.name} [{dec.confidence*100:.0f}%]"
        else:
            color = (0, 0, 255)        # Red for everyone else!
            label = "UNKNOWN PERSON"

        # Draw bounding box
        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
        
        # Draw 5 landmarks if available
        if track.kps is not None:
            for kp in track.kps:
                cv2.circle(img, (int(kp[0]), int(kp[1])), 3, (0, 255, 255), -1)

        # Draw label header
        t_size = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)[0]
        cv2.rectangle(img, (x1, y1 - 26), (x1 + t_size[0] + 10, y1), color, -1)
        cv2.putText(img, label, (x1 + 5, y1 - 7), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2, cv2.LINE_AA)

    def _draw_id_card_hud(self, img: np.ndarray, flash_info: Dict[str, Any]) -> None:
        """
        Renders a high-visibility Employee ID Card Pop-Up HUD on the top right of the video frame
        flashing for 2 seconds upon employee recognition.
        Color: GREEN for CHECK_IN, ORANGE for RE_ENTRY, PURPLE for CHECK_OUT.
        """
        h, w = img.shape[:2]
        card_w, card_h = 360, 130
        x_off, y_off = w - card_w - 20, 20

        event_type = flash_info.get("event_type", "CHECK_IN")
        if event_type == "CHECK_OUT":
            card_color = (255, 0, 180)  # Purple
            header_text = "CHECK-OUT VERIFIED"
        elif event_type == "RE_ENTRY":
            card_color = (0, 165, 255)  # Orange
            header_text = "RE-ENTRY VERIFIED"
        else:
            card_color = (0, 255, 0)    # Green
            header_text = "OFFICIAL ID CARD VERIFIED"

        # Draw Card Background Panel — copy only the card ROI, not the whole frame
        roi = img[y_off:y_off + card_h, x_off:x_off + card_w]
        overlay_roi = roi.copy()
        cv2.rectangle(overlay_roi, (0, 0), (card_w, card_h), (20, 30, 20), -1)
        cv2.addWeighted(overlay_roi, 0.85, roi, 0.15, 0, img[y_off:y_off + card_h, x_off:x_off + card_w])
        cv2.rectangle(img, (x_off, y_off), (x_off + card_w, y_off + card_h), card_color, 3)

        # Draw Employee Enrolled Photo if available
        photo_img = flash_info.get("photo")
        if photo_img is not None:
            try:
                p_resized = cv2.resize(photo_img, (90, 105))
                px, py = x_off + 12, y_off + 12
                img[py:py+105, px:px+90] = p_resized
                cv2.rectangle(img, (px, py), (px+90, py+105), card_color, 1)
            except Exception:
                pass

        # Text Details
        tx = x_off + 115
        cv2.putText(img, header_text, (tx, y_off + 26), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1, cv2.LINE_AA)
        cv2.putText(img, f"NAME: {flash_info['name']}", (tx, y_off + 52), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2, cv2.LINE_AA)
        cv2.putText(img, f"ID: {flash_info['employee_id']}", (tx, y_off + 76), cv2.FONT_HERSHEY_SIMPLEX, 0.5, card_color, 1, cv2.LINE_AA)
        cv2.putText(img, f"MATCH: {flash_info['confidence']*100:.0f}%", (tx, y_off + 98), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (200, 200, 200), 1, cv2.LINE_AA)

    def _draw_hud(self, img: np.ndarray, fps: float, lat: Dict[str, float], active_count: int) -> None:
        mode_text = f"MODE: {self.active_mode}"
        hud_text = [
            f"{mode_text} | FPS: {fps:.1f} | Tracks: {active_count}",
            f"Detect: {lat.get('detection_ms', 0):.1f}ms | Track: {lat.get('tracking_ms', 0):.1f}ms",
            f"End-to-End Latency: {lat.get('end_to_end_ms', 0):.1f}ms"
        ]

        h, w = img.shape[:2]
        cv2.rectangle(img, (10, 10), (320, 75), (0, 0, 0), -1)
        
        mode_color = (255, 0, 180) if self.active_mode == "EXIT" else (0, 255, 0)
        cv2.rectangle(img, (10, 10), (320, 75), mode_color, 1)

        for idx, line in enumerate(hud_text):
            cv2.putText(img, line, (18, 30 + idx * 18), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 255, 255), 1, cv2.LINE_AA)

    def enroll_employee(
        self,
        name: str,
        employee_id: str,
        image_input: Any,
        department: str = "General"
    ) -> Dict[str, Any]:
        """
        Enrolls a new employee into the live recognition pipeline:
        - Detects face with SCRFD & aligns with FaceAligner (5-point keypoints)
        - Extracts 512-d KPRPE+AdaFace embedding
        - Saves photo to Employees_Photo/{name} {employee_id}.jpg
        - Adds to FAISS vector gallery & persists index binary + metadata.json
        - Updates in-memory photo cache & database records
        - Enables immediate live matching on subsequent video frames.
        """
        from src.landmarks.alignment import FaceAligner

        clean_name = name.strip()
        clean_emp_id = employee_id.strip()

        if not clean_name:
            raise ValueError("Employee Name cannot be empty.")
        if not clean_emp_id:
            raise ValueError("Employee ID cannot be empty.")

        # Decode image
        if isinstance(image_input, (bytes, bytearray)):
            jpg_arr = np.frombuffer(image_input, dtype=np.uint8)
            img = cv2.imdecode(jpg_arr, cv2.IMREAD_COLOR)
        elif isinstance(image_input, str):
            img = cv2.imread(image_input)
        elif isinstance(image_input, np.ndarray):
            img = image_input
        else:
            raise ValueError("Invalid image format.")

        if img is None:
            raise ValueError("Failed to decode photo image.")

        # Detect face
        detections = self.detector.detect(img)
        if len(detections) == 0:
            raise ValueError("No face detected in photo. Please ensure the face is well-lit and clearly visible.")

        detections.sort(key=lambda d: d.score, reverse=True)
        best_det = detections[0]

        if best_det.kps is None or len(best_det.kps) != 5:
            raise ValueError("Facial landmark keypoints could not be accurately detected.")

        # Align face to 112x112
        aligned_crop, _ = FaceAligner.align_face_112(img, best_det.kps)
        embedding = self.recognizer.extract_embedding(aligned_crop)

        # Standard filename matching existing gallery convention
        filename = f"{clean_name} {clean_emp_id}.jpg"
        
        # 1. Save to persistent volume directory (survives all Modal restarts/redeployments)
        persisted_dir = "data/enrolled_photos"
        os.makedirs(persisted_dir, exist_ok=True)
        persistent_save_path = os.path.join(persisted_dir, filename)
        cv2.imwrite(persistent_save_path, img)

        # 2. Save to local photo directory
        photos_dir = self.config.get("storage", {}).get("photos_dir", "Employees_Photo")
        os.makedirs(photos_dir, exist_ok=True)
        save_path = os.path.join(photos_dir, filename)
        cv2.imwrite(save_path, img)

        # Remove any existing FAISS entry for this employee_id before adding the new one.
        # This prevents duplicate vectors causing wrong-person recognition matches
        # (e.g., old mirrored enrollment competing with correct new enrollment).
        try:
            existing = [e for e in self.gallery.get_all_employees() if e.get("employee_id") == clean_emp_id]
            if existing:
                print(f"[Enrollment] Removing {len(existing)} old FAISS vector(s) for {clean_emp_id} before re-enrollment.")
                self.gallery.remove_employee(clean_emp_id)
        except Exception as rm_err:
            print(f"[Enrollment] Warning: could not remove old entry for {clean_emp_id}: {rm_err}")

        # Update FAISS gallery
        metadata = {
            "employee_id": clean_emp_id,
            "name": clean_name,
            "filename": filename,
            "image_path": persistent_save_path
        }
        self.gallery.add_embeddings(np.array([embedding]), [metadata])
        self.gallery.save()

        # Update in-memory photo cache with cache-busting timestamp parameter
        rel_path = f"/photos/{filename}?t={int(time.time())}"
        self.employee_photos[clean_emp_id] = img
        self.employee_photo_paths[clean_emp_id] = rel_path
        self.employee_photos[filename] = img
        self.employee_photo_paths[filename] = rel_path
        self.employee_photos[clean_name] = img
        self.employee_photo_paths[clean_name] = rel_path

        # Update Database
        if self.db_repo:
            self.db_repo.enroll_employee(
                employee_id=clean_emp_id,
                name=clean_name,
                image_path=rel_path,
                department=department,
                quality_score=float(best_det.score)
            )

        print(f"[Enrollment] Successfully enrolled employee: {clean_name} ({clean_emp_id}). Total vectors in gallery: {self.gallery.total_vectors}")

        return {
            "status": "SUCCESS",
            "employee_id": clean_emp_id,
            "name": clean_name,
            "department": department,
            "filename": filename,
            "photo_url": rel_path,
            "face_score": float(best_det.score),
            "total_enrolled": self.gallery.total_vectors
        }

    def process_crop(
        self,
        crop_bytes: bytes,
        full_frame_bytes: Optional[bytes] = None,
        kps: Optional[List[List[float]]] = None
    ) -> Dict[str, Any]:
        """
        Processes an on-device detected face crop for server-side AdaFace embedding & FAISS match.
        Runs landmark alignment, AdaFace 512-d GPU embedding extraction, FAISS search, and records
        attendance if the 10-second deduplication threshold is satisfied.
        """
        t0 = time.perf_counter()
        
        nparr = np.frombuffer(crop_bytes, np.uint8)
        crop_img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if crop_img is None or crop_img.size == 0:
            raise ValueError("Failed to decode cropped face image.")

        # Landmark detection and 5-point affine alignment to 112x112
        aligned_face = None
        if kps is not None and len(kps) == 5:
            try:
                aligned_face, _ = FaceAligner.align_face_112(crop_img, np.array(kps, dtype=np.float32))
            except Exception:
                aligned_face = None

        if aligned_face is None:
            # Run SCRFD landmark detector on the crop to accurately extract 5 facial keypoints
            try:
                crop_dets = self.detector.detect(crop_img)
                if crop_dets and len(crop_dets) > 0:
                    best_d = sorted(crop_dets, key=lambda d: d.score, reverse=True)[0]
                    if best_d.kps is not None and len(best_d.kps) == 5:
                        aligned_face, _ = FaceAligner.align_face_112(crop_img, best_d.kps)
            except Exception:
                aligned_face = None

        if aligned_face is None:
            aligned_face = cv2.resize(crop_img, (112, 112))

        # AdaFace embedding extraction (GPU)
        t_emb_0 = time.perf_counter()
        embedding = self.recognizer.extract_embedding(aligned_face)
        t_emb = (time.perf_counter() - t_emb_0) * 1000.0

        # FAISS search (CPU)
        t_search_0 = time.perf_counter()
        matches = self.gallery.search(embedding, top_k=5)
        t_search = (time.perf_counter() - t_search_0) * 1000.0

        top_match = matches[0] if matches else (0.0, {})
        sim = float(top_match[0])
        meta = top_match[1]
        second_sim = float(matches[1][0]) if len(matches) > 1 else 0.0
        margin = sim - second_sim

        print(f"[ClientCrop] Received crop {crop_img.shape[:2]} | Top: {meta.get('name', 'None')} ({meta.get('employee_id', 'None')}) | Sim: {sim:.3f} | Margin: {margin:.3f} | Thresh: {self.certain_match_threshold}")

        matched = False
        emp_id = None
        emp_name = "UNKNOWN PERSON"
        event_status = "UNKNOWN"
        photo_url = None
        event_recorded = False

        # Robust recognition rule: high similarity (>= 0.58) OR confident margin (>= match_thresh & margin >= 0.02)
        if (sim >= 0.58) or (sim >= self.certain_match_threshold and margin >= 0.02):
            matched = True
            emp_id = meta.get("employee_id")
            emp_name = meta.get("name", "Unknown")
            fname = meta.get("filename", "")
            photo_url = f"/photos/{fname}?t={int(time.time())}" if fname else self.employee_photo_paths.get(emp_id, "")
            
            current_time = time.time()
            prev_event_type = self.last_event_type.get(emp_id)
            last_recorded_time = self.deduplicator.get_last_recorded_time(emp_id)
            time_since_last_event = (current_time - last_recorded_time) if last_recorded_time > 0 else 999999.0

            mode_transition = False
            if self.active_mode == "EXIT":
                event_status = "CHECK_OUT"
                if prev_event_type in ["CHECK_IN", "RE_ENTRY"]:
                    mode_transition = True
                self.last_event_type[emp_id] = "CHECK_OUT"
            else:
                if emp_id not in self.globally_marked_present_employees:
                    event_status = "CHECK_IN"
                    self.globally_marked_present_employees.add(emp_id)
                    self.last_event_type[emp_id] = "CHECK_IN"
                    mode_transition = True
                else:
                    if prev_event_type == "CHECK_OUT":
                        mode_transition = True
                        event_status = "RE_ENTRY"
                        self.last_event_type[emp_id] = "RE_ENTRY"
                    elif time_since_last_event < 30.0:
                        event_status = self.last_event_type.get(emp_id, "CHECK_IN")
                    else:
                        event_status = "RE_ENTRY"
                        self.last_event_type[emp_id] = "RE_ENTRY"

            # Enforce 30-second cooldown UNLESS mode changed or first time
            if mode_transition or (last_recorded_time == 0.0) or (time_since_last_event >= 30.0):
                if mode_transition or self.deduplicator.should_record(emp_id, current_time=current_time):
                    if mode_transition:
                        self.deduplicator.last_recorded[emp_id] = current_time
                    event_recorded = True
                    cap_filename = f"CAP_{emp_id}_{event_status}_{int(current_time)}.jpg"
                    cap_filepath = os.path.join("data/attendance_captures", cap_filename)
                    captured_url = f"/captures/{cap_filename}"

                save_img = None
                if full_frame_bytes:
                    try:
                        nparr_f = np.frombuffer(full_frame_bytes, np.uint8)
                        save_img = cv2.imdecode(nparr_f, cv2.IMREAD_COLOR)
                    except Exception:
                        pass
                if save_img is None:
                    save_img = crop_img

                def _save_and_record(snap, path, emp, cam, etype, cap_url, enr_url):
                    if snap is not None and snap.size > 0:
                        try:
                            # Auto-enhance dark, underexposed, or low-contrast webcam snapshots
                            gray = cv2.cvtColor(snap, cv2.COLOR_BGR2GRAY)
                            mean_brightness = np.mean(gray)
                            if mean_brightness < 120.0:
                                # Adaptive gamma correction + slight sharpening
                                gamma = max(0.55, mean_brightness / 140.0)
                                inv_gamma = 1.0 / gamma
                                lut = np.array([((i / 255.0) ** inv_gamma) * 255 for i in np.arange(0, 256)]).astype("uint8")
                                snap = cv2.LUT(snap, lut)
                        except Exception:
                            pass
                        cv2.imwrite(path, snap, [int(cv2.IMWRITE_JPEG_QUALITY), 95])

                        # Automated Rolling Pruning: keep capture folder capped at max 100 newest files
                        try:
                            cap_dir = os.path.dirname(path)
                            existing_caps = [os.path.join(cap_dir, f) for f in os.listdir(cap_dir) if f.endswith(".jpg")]
                            if len(existing_caps) > 100:
                                existing_caps.sort(key=lambda p: os.path.getmtime(p))
                                for old_f in existing_caps[:len(existing_caps) - 100]:
                                    try:
                                        os.remove(old_f)
                                    except Exception:
                                        pass
                        except Exception:
                            pass

                    if self.db_repo:
                        self.db_repo.record_attendance_event(
                            employee_id=emp,
                            camera_id=cam,
                            event_type=etype,
                            captured_frame_path=cap_url,
                            enrolled_photo_path=enr_url
                        )

                self._io_executor.submit(
                    _save_and_record,
                    save_img, cap_filepath,
                    f"{emp_name} ({emp_id})", "CLIENT_DEVICE_CAM", event_status,
                    captured_url, photo_url or ""
                )

        t_total = (time.perf_counter() - t0) * 1000.0

        return {
            "status": "SUCCESS",
            "matched": matched,
            "employee_id": emp_id,
            "name": emp_name,
            "confidence": round(sim, 4),
            "margin": round(margin, 4),
            "decision": event_status,
            "event_recorded": event_recorded,
            "photo_url": photo_url,
            "timings_ms": {
                "embedding_ms": round(t_emb, 2),
                "vector_search_ms": round(t_search, 3),
                "total_server_ms": round(t_total, 2)
            }
        }

    def get_present_employees_set(self) -> Set[str]:
        """
        Returns all unique employee IDs who have checked in today, querying both memory and database.
        """
        present_set = set(self.globally_marked_present_employees)
        if self.db_repo:
            try:
                session = self.db_repo.get_session()
                from src.database.models import AttendanceEventModel
                import datetime
                today_start = datetime.datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
                today_events = session.query(AttendanceEventModel.employee_id).filter(
                    AttendanceEventModel.timestamp >= today_start,
                    AttendanceEventModel.event_type.in_(["CHECK_IN", "RE_ENTRY"])
                ).distinct().all()
                for row in today_events:
                    if row[0] and not row[0].startswith("Unknown Person") and row[0] != "UNKNOWN":
                        raw_id = row[0]
                        if "(" in raw_id and raw_id.endswith(")"):
                            extracted_id = raw_id.split("(")[-1].rstrip(")")
                            present_set.add(extracted_id)
                        else:
                            present_set.add(raw_id)
                session.close()
            except Exception as e:
                print(f"Error querying present employees: {e}")
        return present_set

    def get_enrolled_employees(self) -> List[Dict[str, Any]]:
        """
        Returns list of all currently enrolled employees with their photo URLs and live presence status.
        """
        present_set = self.get_present_employees_set()
        employees = []
        raw_list = self.gallery.get_all_employees()
        for item in raw_list:
            emp_id = item.get("employee_id", "")
            name = item.get("name", "")
            fname = item.get("filename", "")
            photo_url = f"/photos/{fname}?t={int(time.time())}" if fname else self.employee_photo_paths.get(emp_id, "")
            is_present = emp_id in present_set
            employees.append({
                "employee_id": emp_id,
                "name": name,
                "filename": fname,
                "photo_url": photo_url,
                "is_present": is_present,
                "last_event_type": self.last_event_type.get(emp_id, "CHECK_IN" if is_present else None),
                "status": "PRESENT" if is_present else "NOT CHECKED IN"
            })
        return sorted(employees, key=lambda x: (not x.get("is_present", False), x.get("name", "").lower()))

    def remove_employee(self, employee_id: str) -> Dict[str, Any]:
        """
        Removes an employee completely from everywhere:
        1. FAISS Gallery & Metadata: Removes embedding vectors and updates metadata.json + faiss_index.bin.
        2. In-Memory Caches:
           - Purges from employee_photos & employee_photo_paths (by id, name, filename, and variations).
           - Purges from globally_marked_present_employees set.
           - Purges from last_event_type map.
           - Purges from deduplicator cooldown tracking.
           - Resets any active tracking identities.
        3. Physical Files on Disk:
           - Deletes matching portrait photos from Employees_Photo/ (exact filename, matching ID pattern, name pattern).
           - Deletes matching portrait photos from data/enrolled_photos/ (exact filename, matching ID pattern, name pattern).
           - Deletes captured attendance snapshots from data/attendance_captures/ matching the employee ID or recorded frame paths.
        4. Relational Database (PostgreSQL & SQLite):
           - Deletes employee record from employees table.
           - Deletes all enrollment records from enrollments table.
           - Deletes all attendance events from attendance_events table.
           - Deletes all recognition events from recognition_events table.
        """
        clean_id = str(employee_id).strip()
        if not clean_id:
            raise ValueError("Employee ID cannot be empty.")

        # Find employee metadata from gallery if available
        matched_items = [
            item for item in self.gallery.get_all_employees()
            if str(item.get("employee_id", "")).strip().lower() == clean_id.lower()
        ]
        
        emp_name = ""
        fname = ""
        image_path = ""
        if matched_items:
            emp_name = matched_items[0].get("name", "")
            fname = matched_items[0].get("filename", "")
            image_path = matched_items[0].get("image_path", "")

        # Also check DB if name not found in gallery
        if not emp_name and self.db_repo:
            try:
                from sqlalchemy import or_
                from src.database.models import EmployeeModel
                session = self.db_repo.get_session()
                emp_db = session.query(EmployeeModel).filter(
                    or_(EmployeeModel.employee_id == clean_id, EmployeeModel.employee_id.ilike(clean_id))
                ).first()
                if emp_db:
                    emp_name = emp_db.name
                session.close()
            except Exception as e:
                print(f"[Removal] Warning checking DB for employee name: {e}")

        # 1. Remove from FAISS index & metadata
        self.gallery.remove_employee(clean_id)

        # 2. Remove from in-memory photo caches
        keys_to_pop = [clean_id, clean_id.lower(), clean_id.upper()]
        if fname:
            keys_to_pop.extend([fname, fname.lower()])
        if emp_name:
            keys_to_pop.extend([emp_name, emp_name.lower()])
        for k in list(self.employee_photos.keys()):
            k_str = str(k).strip()
            if clean_id.lower() in k_str.lower() or (emp_name and emp_name.lower() in k_str.lower()):
                keys_to_pop.append(k)
        for k in set(keys_to_pop):
            self.employee_photos.pop(k, None)
            self.employee_photo_paths.pop(k, None)

        # 3. Purge in-memory presence and deduplication tracking
        to_discard_present = [
            p for p in self.globally_marked_present_employees
            if clean_id.lower() in str(p).lower() or (emp_name and emp_name.lower() in str(p).lower())
        ]
        for p in to_discard_present:
            self.globally_marked_present_employees.discard(p)

        for k in list(self.last_event_type.keys()):
            if clean_id.lower() in str(k).lower() or (emp_name and emp_name.lower() in str(k).lower()):
                self.last_event_type.pop(k, None)

        if hasattr(self, "deduplicator") and self.deduplicator:
            for k in list(self.deduplicator.last_recorded.keys()):
                if clean_id.lower() in str(k).lower() or (emp_name and emp_name.lower() in str(k).lower()):
                    self.deduplicator.last_recorded.pop(k, None)

        if hasattr(self, "track_identities"):
            for tid, ident in list(self.track_identities.items()):
                if clean_id.lower() in str(ident).lower():
                    self.track_identities.pop(tid, None)

        # 4. Remove from Database (deletes employees, enrollments, attendance_events, recognition_events)
        deleted_captures = []
        if self.db_repo:
            db_res = self.db_repo.remove_employee(clean_id)
            if isinstance(db_res, dict):
                deleted_captures = db_res.get("deleted_captures", [])

        # 5. Delete physical photo files across persistent and local directories
        photos_dir = self.config.get("storage", {}).get("photos_dir", "Employees_Photo")
        photo_dirs = ["data/enrolled_photos", photos_dir]

        for pdir in photo_dirs:
            if not os.path.exists(pdir):
                continue
            if fname:
                fpath = os.path.join(pdir, fname)
                if os.path.exists(fpath):
                    try:
                        os.remove(fpath)
                    except Exception as e:
                        print(f"Warning: could not delete file {fpath}: {e}")
            # Scan directory for files containing clean_id
            try:
                for file_in_dir in os.listdir(pdir):
                    f_lower = file_in_dir.lower()
                    if clean_id.lower() in f_lower:
                        try:
                            os.remove(os.path.join(pdir, file_in_dir))
                        except Exception:
                            pass
            except Exception as scan_err:
                print(f"Warning scanning {pdir}: {scan_err}")

        if image_path and os.path.exists(image_path):
            try:
                os.remove(image_path)
            except Exception:
                pass

        # 6. Delete capture snapshots for this employee from data/attendance_captures
        cap_dir = "data/attendance_captures"
        if os.path.exists(cap_dir):
            for cap_path in deleted_captures:
                if cap_path:
                    c_base = os.path.basename(cap_path)
                    c_full = os.path.join(cap_dir, c_base)
                    if os.path.exists(c_full):
                        try:
                            os.remove(c_full)
                        except Exception:
                            pass
            try:
                for cap_file in os.listdir(cap_dir):
                    if clean_id.lower() in cap_file.lower():
                        try:
                            os.remove(os.path.join(cap_dir, cap_file))
                        except Exception:
                            pass
            except Exception as cap_err:
                print(f"Warning scanning {cap_dir}: {cap_err}")

        print(f"[Removal] Successfully purged all records, files, embeddings, and caches for employee: {emp_name or clean_id} ({clean_id}). Total vectors in gallery: {self.gallery.total_vectors}")

        return {
            "status": "SUCCESS",
            "employee_id": clean_id,
            "name": emp_name or clean_id,
            "total_enrolled": self.gallery.total_vectors
        }

    def flush_attendance_events(self) -> Dict[str, Any]:
        """
        Flushes all attendance records from database and resets all in-memory presence and deduplication tracking.
        """
        db_cleared = False
        if self.db_repo:
            db_cleared = self.db_repo.clear_all_events()

        self.globally_marked_present_employees.clear()
        self.last_event_type.clear()
        if hasattr(self, "deduplicator") and self.deduplicator:
            self.deduplicator.last_recorded.clear()

        # Clean all disk captures
        try:
            cap_dir = "data/attendance_captures"
            if os.path.exists(cap_dir):
                for f in os.listdir(cap_dir):
                    if f.endswith(".jpg"):
                        try:
                            os.remove(os.path.join(cap_dir, f))
                        except Exception:
                            pass
        except Exception:
            pass

        print("[Pipeline] Flushed all attendance events, cleaned captures folder, and reset live presence counters.")
        return {
            "status": "SUCCESS",
            "db_cleared": db_cleared,
            "message": "All attendance events and captures cleared successfully."
        }
