import cv2
from typing import Tuple, Optional, Union
import numpy as np
from src.video.base import VideoSource

class CameraVideoSource(VideoSource):
    """
    USB or Jetson CSI Camera Video Source.
    """

    def __init__(self, device_id: Union[int, str] = 0, width: int = 1280, height: int = 720, target_fps: float = 30.0):
        self.device_id = device_id
        
        # Open USB or CSI camera
        if isinstance(device_id, str) and "nvarguscamerasrc" in device_id:
            # Jetson CSI camera GStreamer pipeline
            self.cap = cv2.VideoCapture(device_id, cv2.CAP_GSTREAMER)
        else:
            self.cap = cv2.VideoCapture(int(device_id))
            self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
            self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
            self.cap.set(cv2.CAP_PROP_FPS, target_fps)

        if not self.cap.isOpened():
            raise RuntimeError(f"Failed to open Camera device: {device_id}")

        fps = self.cap.get(cv2.CAP_PROP_FPS)
        self._fps = fps if fps > 0 else target_fps
        w = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        self._resolution = (w if w > 0 else width, h if h > 0 else height)

    def read(self) -> Tuple[bool, Optional[np.ndarray]]:
        if not self.cap or not self.cap.isOpened():
            return False, None
        return self.cap.read()

    def is_opened(self) -> bool:
        return self.cap is not None and self.cap.isOpened()

    def release(self) -> None:
        if self.cap:
            self.cap.release()
            self.cap = None

    @property
    def fps(self) -> float:
        return self._fps

    @property
    def resolution(self) -> Tuple[int, int]:
        return self._resolution
