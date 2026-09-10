import cv2
import time
from typing import Tuple, Optional
import numpy as np
from src.video.base import VideoSource

class RTSPVideoSource(VideoSource):
    """
    RTSP Stream Video Source. Supports OpenCV backend and GStreamer RTSP decoding pipeline.
    """

    def __init__(self, rtsp_url: str, use_gstreamer: bool = False):
        self.rtsp_url = rtsp_url
        self.use_gstreamer = use_gstreamer

        if use_gstreamer:
            # GStreamer hardware acceleration pipeline for Jetson / NVDEC
            pipeline = (
                f"rtspsrc location={rtsp_url} latency=100 ! "
                f"rtph264depay ! h264parse ! nvv4l2decoder ! "
                f"nvvidconv ! video/x-raw, format=BGRx ! "
                f"videoconvert ! video/x-raw, format=BGR ! appsink drop=true"
            )
            self.cap = cv2.VideoCapture(pipeline, cv2.CAP_GSTREAMER)
        else:
            self.cap = cv2.VideoCapture(rtsp_url)

        if not self.cap.isOpened():
            raise RuntimeError(f"Failed to open RTSP stream: {rtsp_url}")

        fps = self.cap.get(cv2.CAP_PROP_FPS)
        self._fps = fps if fps > 0 else 30.0
        width = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        self._resolution = (width, height)

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
