from abc import ABC, abstractmethod
from typing import Tuple, Optional
import numpy as np

class VideoSource(ABC):
    """
    Abstract Base Class for Video Sources.
    Ensures RTSP, File, and Live Camera sources adhere to the exact same interface.
    """
    
    @abstractmethod
    def read(self) -> Tuple[bool, Optional[np.ndarray]]:
        """
        Reads the next frame.
        Returns (success: bool, frame: np.ndarray BGR).
        """
        pass

    @abstractmethod
    def is_opened(self) -> bool:
        """Returns True if the video source is open and active."""
        pass

    @abstractmethod
    def release(self) -> None:
        """Releases hardware resources / video streams."""
        pass

    @property
    @abstractmethod
    def fps(self) -> float:
        """Returns target or native frames per second."""
        pass

    @property
    @abstractmethod
    def resolution(self) -> Tuple[int, int]:
        """Returns (width, height) of the stream."""
        pass
