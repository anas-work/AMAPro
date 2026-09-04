import os
import datetime
from typing import Optional, List, Dict, Any
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, Session
from src.database.models import Base, EmployeeModel, EnrollmentModel, CameraModel, RecognitionEventModel, AttendanceEventModel, get_ist_now

class AttendanceRepository:
    """
    Database Repository Abstraction.
    Handles PostgreSQL connection with automatic SQLite local fallback mode if DB server is unreachable.
    """

    def __init__(self, db_url: str = "sqlite:///data/attendance.db"):
        self.db_url = db_url
        self.engine = None
        self.SessionLocal = None
        self._init_db()

    def _init_db(self) -> None:
        try:
            # For PostgreSQL: limit pool size to avoid per-call overhead
            engine_kwargs: dict = {"pool_pre_ping": True}
            if self.db_url.startswith("postgresql"):
                engine_kwargs.update({"pool_size": 5, "max_overflow": 10})
            self.engine = create_engine(self.db_url, **engine_kwargs)
            Base.metadata.create_all(bind=self.engine)
            self.SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)
            print(f"Database initialized successfully ({self.db_url})")
        except Exception as e:
            print(f"Warning: Primary DB initialization error ({e}). Falling back to local SQLite...")
            os.makedirs("data", exist_ok=True)
            fallback_url = "sqlite:///data/attendance.db"
            self.engine = create_engine(
                fallback_url,
                connect_args={"check_same_thread": False, "timeout": 30.0},
            )
            # Apply safe PRAGMAs for network volume persistence: avoid WAL mmap shared memory lock conflicts
            from sqlalchemy import event as sa_event
            @sa_event.listens_for(self.engine, "connect")
            def configure_sqlite_connection(dbapi_conn, _):
                dbapi_conn.execute("PRAGMA journal_mode=DELETE")
                dbapi_conn.execute("PRAGMA synchronous=NORMAL")
                dbapi_conn.execute("PRAGMA busy_timeout=10000")
                dbapi_conn.execute("PRAGMA cache_size=4000")
            Base.metadata.create_all(bind=self.engine)
            self.SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)

    def get_session(self) -> Session:
        return self.SessionLocal()

    def clear_all_events(self) -> bool:
        """
        Clears all attendance and recognition events so the system starts with a completely empty activity feed.
        """
        session = self.get_session()
        try:
            session.query(AttendanceEventModel).delete()
            session.query(RecognitionEventModel).delete()
            session.commit()
            print("Successfully cleared all attendance records from database.")
            return True
        except Exception as e:
            session.rollback()
            print(f"Error clearing attendance events: {e}")
            return False
        finally:
            session.close()

    def enroll_employee(
        self,
        employee_id: str,
        name: str,
        image_path: str,
        department: str = "General",
        quality_score: float = 1.0
    ) -> bool:
        session = self.get_session()
        try:
            # Upsert EmployeeModel record
            emp = session.query(EmployeeModel).filter_by(employee_id=employee_id).first()
            if not emp:
                emp = EmployeeModel(
                    employee_id=employee_id,
                    name=name,
                    department=department,
                    status="ACTIVE",
                    created_at=get_ist_now(),
                    updated_at=get_ist_now()
                )
                session.add(emp)
            else:
                # Always update name, department, image_path to latest enrollment
                emp.name = name
                emp.department = department
                emp.updated_at = get_ist_now()

            # Remove stale enrollment records for this ID so image_path is always fresh
            session.query(EnrollmentModel).filter_by(employee_id=employee_id).delete(synchronize_session=False)

            enrollment = EnrollmentModel(
                employee_id=employee_id,
                image_path=image_path,
                embedding_identifier=f"{employee_id}_emb",
                quality_score=quality_score,
                created_at=get_ist_now()
            )
            session.add(enrollment)
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            print(f"Error enrolling employee in DB: {e}")
            return False
        finally:
            session.close()

    def remove_employee(self, employee_id: str) -> bool:
        """
        Removes employee and associated enrollment records from the database.
        """
        session = self.get_session()
        try:
            clean_id = employee_id.strip()
            session.query(EnrollmentModel).filter_by(employee_id=clean_id).delete(synchronize_session=False)
            session.query(EmployeeModel).filter_by(employee_id=clean_id).delete(synchronize_session=False)
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            print(f"Error removing employee from DB: {e}")
            return False
        finally:
            session.close()

    def record_recognition_event(
        self,
        camera_id: str,
        track_id: int,
        decision: str,
        employee_id: Optional[str] = None,
        similarity: float = 0.0,
        quality_score: float = 0.0
    ) -> bool:
        session = self.get_session()
        try:
            evt = RecognitionEventModel(
                camera_id=camera_id,
                employee_id=employee_id if employee_id != "UNKNOWN" else None,
                track_id=track_id,
                similarity=similarity,
                quality_score=quality_score,
                decision=decision,
                timestamp=get_ist_now()
            )
            session.add(evt)
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            print(f"Error writing recognition event: {e}")
            return False
        finally:
            session.close()

    def record_attendance_event(
        self,
        employee_id: str,
        camera_id: str,
        event_type: str = "CHECK_IN",
        captured_frame_path: Optional[str] = None,
        enrolled_photo_path: Optional[str] = None
    ) -> bool:
        session = self.get_session()
        try:
            evt = AttendanceEventModel(
                employee_id=employee_id,
                camera_id=camera_id,
                event_type=event_type,
                captured_frame_path=captured_frame_path,
                enrolled_photo_path=enrolled_photo_path,
                timestamp=get_ist_now()
            )
            session.add(evt)
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            print(f"Error writing attendance event: {e}")
            return False
        finally:
            session.close()

    def get_recent_attendance(self, limit: int = 500) -> List[Dict[str, Any]]:
        session = self.get_session()
        try:
            records = session.query(AttendanceEventModel).order_by(AttendanceEventModel.timestamp.desc()).limit(limit).all()
            return [
                {
                    "id": r.id,
                    "employee_id": r.employee_id,
                    "camera_id": r.camera_id,
                    "timestamp": r.timestamp.isoformat() if r.timestamp else "",
                    "time_str": r.timestamp.strftime("%I:%M:%S %p") if r.timestamp else "",
                    "date_str": r.timestamp.strftime("%d %b %Y, %I:%M:%S %p") if r.timestamp else "",
                    "event_type": r.event_type,
                    "captured_frame_path": r.captured_frame_path,
                    "enrolled_photo_path": r.enrolled_photo_path
                }
                for r in records
            ]
        except Exception as e:
            print(f"Error reading recent attendance: {e}")
            return []
        finally:
            session.close()
