"""可注入的时钟，便于测试调度逻辑。"""

from __future__ import annotations

import time
from typing import Protocol


class Clock(Protocol):
    def monotonic(self) -> float: ...

    def now(self) -> float: ...

    def sleep(self, seconds: float) -> None: ...


class SystemClock:
    def monotonic(self) -> float:
        return time.monotonic()

    def now(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        # 支持响应停止事件的 sleep 由调用方实现；这里只做基础休眠。
        if seconds > 0:
            time.sleep(seconds)


class FakeClock:
    """测试用：sleep 只推进虚拟时间。"""

    def __init__(self, start: float = 1_000_000.0) -> None:
        self._monotonic = start
        self._wall = start

    def monotonic(self) -> float:
        return self._monotonic

    def now(self) -> float:
        return self._wall

    def sleep(self, seconds: float) -> None:
        self.advance(seconds)

    def advance(self, seconds: float) -> None:
        if seconds < 0:
            raise ValueError("cannot advance negative time")
        self._monotonic += seconds
        self._wall += seconds
