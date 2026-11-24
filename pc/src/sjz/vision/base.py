"""视觉能力的抽象协议。

OCR 与模板匹配都通过 Protocol 注入，业务代码不直接依赖
RapidOCR / Tesseract / OpenCV 的具体实现，方便测试和替换。
"""

from __future__ import annotations

from typing import Protocol

import numpy as np

from sjz.core.types import Detection, Rect


class OcrEngine(Protocol):
    def read_text(self, image: np.ndarray, *, hint: str = "") -> str:
        """识别一段图像中的文字，返回原始文本（可能含噪声）。"""
        ...

    def read_number(self, image: np.ndarray, *, hint: str = "") -> int | None:
        """识别价格/数量类整数；失败返回 None。"""
        ...

    def close(self) -> None: ...


class TemplateMatcher(Protocol):
    def find(
        self,
        image: np.ndarray,
        name: str,
        *,
        threshold: float | None = None,
        region: Rect | None = None,
    ) -> Detection | None: ...

    def score(self, image: np.ndarray, name: str, *, region: Rect | None = None) -> float: ...
