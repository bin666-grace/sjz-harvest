"""输入注入抽象。

业务层只调用 Point 坐标；键盘使用项目内部统一键名
（esc/space/enter/alt/tab 等），由后端映射到具体实现。
"""

from __future__ import annotations

from typing import Protocol

from sjz.core.types import Point


class InputController(Protocol):
    def move(self, point: Point, *, duration_s: float | None = None) -> None: ...

    def click(
        self, point: Point, *, button: str = "left", duration_s: float | None = None
    ) -> None: ...

    def press(self, key: str, *, presses: int = 1, interval_s: float = 0.05) -> None: ...

    def scroll(self, point: Point, clicks: int) -> None: ...

    def close(self) -> None: ...
