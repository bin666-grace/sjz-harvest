"""跨模块共享的数据类型。

这个模块不允许 import 项目内的其他模块，避免循环依赖。
所有坐标统一使用「游戏画面坐标」：以游戏画面左上角为原点，
DPI 缩放、窗口偏移都在采集层/窗口层处理，业务层只认画面坐标。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping

import numpy as np


class Dept(str, Enum):
    """特勤处四个制造部门。"""

    TECH = "tech"        # 技术中心
    WORK = "work"        # 工作台
    MEDICAL = "medical"  # 制药台
    ARMOR = "armor"      # 防具台

    @property
    def label(self) -> str:
        return {
            Dept.TECH: "技术中心",
            Dept.WORK: "工作台",
            Dept.MEDICAL: "制药台",
            Dept.ARMOR: "防具台",
        }[self]


class DeptState(str, Enum):
    """单个部门在总览页上的状态。"""

    IDLE = "idle"          # 空闲中，可以开始制造
    CRAFTING = "crafting"  # 制造中，有剩余时间
    DONE = "done"          # 制造完成，等待收取
    UNKNOWN = "unknown"    # 识别失败，禁止盲操作


class Page(str, Enum):
    """脚本能够识别的页面。"""

    DASHBOARD = "dashboard"        # 特勤处总览（四个部门卡片）
    DEPARTMENT = "department"      # 部门制造列表
    CRAFT_DIALOG = "craft_dialog"  # 材料/开始制造弹窗
    AUCTION = "auction"            # 交易行购买页
    UNKNOWN = "unknown"


class ActionKind(str, Enum):
    FOCUS = "focus"
    CLICK = "click"
    KEY = "key"
    SCROLL = "scroll"
    WAIT = "wait"
    COLLECT = "collect"
    CRAFT = "craft"
    BUY = "buy"
    SKIP = "skip"


@dataclass(frozen=True)
class Point:
    x: float
    y: float

    def as_int(self) -> tuple[int, int]:
        return int(round(self.x)), int(round(self.y))

    def offset(self, dx: float, dy: float) -> "Point":
        return Point(self.x + dx, self.y + dy)


@dataclass(frozen=True)
class Rect:
    x: int
    y: int
    w: int
    h: int

    @property
    def center(self) -> Point:
        return Point(self.x + self.w / 2, self.y + self.h / 2)

    def to_ltrb(self) -> tuple[int, int, int, int]:
        return self.x, self.y, self.x + self.w, self.y + self.h

    def to_tuple(self) -> tuple[int, int, int, int]:
        """(x, y, w, h)"""
        return self.x, self.y, self.w, self.h

    def scaled(self, factor: float) -> "Rect":
        return Rect(
            int(round(self.x * factor)),
            int(round(self.y * factor)),
            int(round(self.w * factor)),
            int(round(self.h * factor)),
        )

    def offset(self, dx: int, dy: int) -> "Rect":
        return Rect(self.x + dx, self.y + dy, self.w, self.h)

    def inset(self, px: int) -> "Rect":
        return Rect(self.x + px, self.y + px, max(1, self.w - 2 * px), max(1, self.h - 2 * px))

    def contains(self, point: Point) -> bool:
        return self.x <= point.x < self.x + self.w and self.y <= point.y < self.y + self.h

    def clipped(self, width: int, height: int) -> "Rect":
        x1 = max(0, min(self.x, width))
        y1 = max(0, min(self.y, height))
        x2 = max(x1, min(self.x + self.w, width))
        y2 = max(y1, min(self.y + self.h, height))
        return Rect(x1, y1, x2 - x1, y2 - y1)

    def is_empty(self) -> bool:
        return self.w <= 0 or self.h <= 0


def rect_from_tuple(value: tuple[int, int, int, int]) -> Rect:
    return Rect(*value)


@dataclass
class Frame:
    """一帧画面。image 为 BGR ndarray。origin 是该帧左上角在屏幕上的坐标。"""

    image: np.ndarray
    captured_at: float = field(default_factory=time.monotonic)
    origin: Point = field(default_factory=lambda: Point(0, 0))
    monitor_index: int = 0

    @property
    def width(self) -> int:
        return int(self.image.shape[1])

    @property
    def height(self) -> int:
        return int(self.image.shape[0])

    def crop(self, rect: Rect) -> np.ndarray:
        r = rect.clipped(self.width, self.height)
        return self.image[r.y : r.y + r.h, r.x : r.x + r.w].copy()


@dataclass(frozen=True)
class Detection:
    """一次视觉检测结果。rect 使用画面坐标。"""

    rect: Rect
    score: float
    label: str = ""


@dataclass(frozen=True)
class DeptStatus:
    dept: Dept
    state: DeptState
    remaining_s: int | None = None
    confidence: float = 1.0
    detail: str = ""

    @property
    def is_actionable(self) -> bool:
        return self.state in (DeptState.IDLE, DeptState.DONE)


@dataclass(frozen=True)
class Observation:
    """对当前画面的一次完整判断。"""

    page: Page
    statuses: Mapping[Dept, DeptStatus] = field(default_factory=dict)
    frame: Frame | None = None
    detail: str = ""

    def status(self, dept: Dept) -> DeptStatus:
        return self.statuses.get(
            dept, DeptStatus(dept=dept, state=DeptState.UNKNOWN, detail="missing")
        )


@dataclass(frozen=True)
class CycleResult:
    statuses: Mapping[Dept, DeptStatus]
    actions: tuple[str, ...]
    next_wake_s: float
    stopped: bool = False
    error: str | None = None
