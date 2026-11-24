"""截屏抽象。

实现要求：
- 返回 BGR ndarray；
- 全黑帧/None 必须重试，最终抛 CaptureError；
- region 使用画面坐标 (x, y, w, h)；
- 不要在这里做任何图像预处理（二值化属于 vision 层）。
"""

from __future__ import annotations

from typing import Protocol

from sjz.core.types import Frame, Rect


class ScreenSource(Protocol):
    def grab(self, region: Rect | None = None) -> Frame: ...

    def size(self) -> tuple[int, int]: ...

    def close(self) -> None: ...
