"""项目异常层级。

设计原则：所有可预期的失败都必须有明确类型，编排层据此决定
「重试 / 等待人工 / 立即停止」。禁止用裸 Exception 表达业务状态。
"""

from __future__ import annotations


class SjzError(Exception):
    """所有项目异常的基类。"""


class ConfigError(SjzError):
    """配置文件缺失、格式错误或字段非法。"""


class ProfileError(ConfigError):
    """UI profile 与当前分辨率/坐标不匹配。"""


class CaptureError(SjzError):
    """截屏失败（全黑、dxcam 初始化失败等）。"""


class WindowNotFound(SjzError):
    """找不到游戏窗口。"""


class WindowNotForeground(SjzError):
    """游戏窗口不在前台，拒绝发送输入。"""


class ResolutionError(SjzError):
    """分辨率不在 profile 支持范围内。"""


class PageNotRecognized(SjzError):
    """当前页面无法确认，脚本必须停下而不是盲点。"""


class OcrError(SjzError):
    """OCR 引擎不可用或输出无法解析。"""


class InputError(SjzError):
    """输入注入失败。"""


class MaterialUnavailable(SjzError):
    """缺少无法从交易行直接购买的兑换材料。"""


class PlanError(SjzError):
    """生产计划数据非法。"""


class PlanExhausted(SjzError):
    """当前部门的生产计划已无剩余数量。"""


class PausedByUser(SjzError):
    """用户触发暂停热键。"""


class StoppedByUser(SjzError):
    """用户触发停止热键。"""


class SafetyLimitReached(SjzError):
    """达到最大循环次数或最长运行时间。"""
