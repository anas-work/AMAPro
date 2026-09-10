import cv2
import time
import os
import glob
import threading
import queue
from typing import Tuple, Optional
import numpy as np
from src.video.base import VideoSource

class FileVideoSource(VideoSource):
    """
    High-performance Threaded Video Source for authentic real-time playback (30 FPS).
    Reads video frames in a dedicated background thread at exact native FPS intervals,
    preventing inference processing latency from slowing down video playback speed.
    """

    def __init__(self, file_path: str = "Employees_Video/Live_Feed.mp4", loop: bool = True, target_fps: Optional[float] = None):
        self.loop = loop
        self.file_path = file_path
        self.cap = None
        self.running = False
        self.thread = None
        self.frame_queue = queue.Queue(maxsize=3)

        self._fps = target_fps or 30.0
        self._resolution = (1280, 720)

        self._init_capture(file_path)
        self.start()

    def _init_capture(self, path: str) -> None:
        search_candidates = [
            path,
            "Employees_Video/Live_Feed.mp4",
            "Employee_Video/Live_Feed.mp4",
            "/h3/anas/Employee_Video/Live_Feed.mp4",
            "/h3/anas/ABLBL_Attendance/Employees_Video/Live_Feed.mp4"
        ]

        found_path = None
        for cand in search_candidates:
            if os.path.exists(cand) and os.path.getsize(cand) > 0:
                found_path = cand
                break

        if not found_path:
            for video_dir in ["Employees_Video", "Employee_Video", "/h3/anas/Employee_Video"]:
                if os.path.exists(video_dir):
                    candidates = glob.glob(os.path.join(video_dir, "*.mp4")) + \
                                 glob.glob(os.path.join(video_dir, "*.avi")) + \
                                 glob.glob(os.path.join(video_dir, "*.mov")) + \
                                 glob.glob(os.path.join(video_dir, "*.mkv"))
                    if candidates:
                        found_path = candidates[0]
                        break

        if found_path and os.path.exists(found_path):
            self.file_path = found_path
            if self.cap:
                self.cap.release()
            self.cap = cv2.VideoCapture(found_path)
            if self.cap.isOpened():
                fps = self.cap.get(cv2.CAP_PROP_FPS)
                if fps > 0:
                    self._fps = fps
                w = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                h = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                if w > 0 and h > 0:
                    self._resolution = (w, h)
                print(f"FileVideoSource initialized: {found_path} ({self._resolution[0]}x{self._resolution[1]} @ {self._fps:.1f} FPS)")

    def restart(self) -> None:
        """
        Rewinds video stream back to frame 0 and flushes queue.
        """
        if self.cap and self.cap.isOpened():
            self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
        while not self.frame_queue.empty():
            try:
                self.frame_queue.get_nowait()
            except queue.Empty:
                break
        print("FileVideoSource restarted from frame 0.")

    def start(self) -> None:
        if not self.running:
            self.running = True
            self.thread = threading.Thread(target=self._reader_loop, daemon=True)
            self.thread.start()

    def _reader_loop(self) -> None:
        frame_interval = 1.0 / self._fps if self._fps > 0 else 0.033
        last_time = time.perf_counter()
        last_valid_frame = None

        while self.running:
            if self.cap is None or not self.cap.isOpened():
                self._init_capture(self.file_path)
                if self.cap is None or not self.cap.isOpened():
                    time.sleep(frame_interval)
                    continue

            # Maintain strict real-time 30 FPS pacing
            now = time.perf_counter()
            elapsed = now - last_time
            sleep_time = frame_interval - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

            ret, frame = self.cap.read()
            last_time = time.perf_counter()

            if not ret or frame is None:
                if self.loop:
                    # Seamless Video Loop: Re-open capture cleanly without black screen gaps
                    self.cap.release()
                    self.cap = cv2.VideoCapture(self.file_path)
                    ret, frame = self.cap.read()
                else:
                    break

            if ret and frame is not None:
                last_valid_frame = frame
                self._put_queue(frame)
            elif last_valid_frame is not None:
                # Fallback to last valid frame to prevent black stream flickers
                self._put_queue(last_valid_frame)

    def _put_queue(self, frame: np.ndarray) -> None:
        if self.frame_queue.full():
            try:
                self.frame_queue.get_nowait()
            except queue.Empty:
                pass
        self.frame_queue.put(frame)

    def read(self) -> Tuple[bool, Optional[np.ndarray]]:
        try:
            frame = self.frame_queue.get(timeout=0.5)
            return True, frame
        except queue.Empty:
            return False, None

    def is_opened(self) -> bool:
        return self.running

    def release(self) -> None:
        self.running = False
        if self.cap:
            self.cap.release()
            self.cap = None

    @property
    def fps(self) -> float:
        return self._fps

    @property
    def resolution(self) -> Tuple[int, int]:
        return self._resolution
