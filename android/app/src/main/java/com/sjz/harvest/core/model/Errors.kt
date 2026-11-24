package com.sjz.harvest.core.model

/**
 * 可预期失败的统一异常层级。编排层只对以下类型做决策：
 * - PageNotRecognized / GameNotForeground：停止并等待人工；
 * - CaptureFailed / InputUnavailable：重试若干次后停止；
 * - SafetyLimitReached / StoppedByUser：正常退出；
 * - 其他未知异常：记录堆栈并停止。
 */
sealed class SjzException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class PageNotRecognized(message: String) : SjzException(message)

    class GameNotForeground(val packageName: String?) :
        SjzException("前台不是目标游戏: ${packageName ?: "unknown"}")

    class AccessibilityServiceUnavailable :
        SjzException("无障碍服务未开启或尚未连接")

    class CaptureFailed(message: String, cause: Throwable? = null) :
        SjzException(message, cause)

    class InputUnavailable(message: String) : SjzException(message)

    class ConfigError(message: String, cause: Throwable? = null) :
        SjzException(message, cause)

    class PlanError(message: String) : SjzException(message)

    class SafetyLimitReached(message: String) : SjzException(message)

    class StoppedByUser : SjzException("用户停止")

    class PausedByUser : SjzException("用户暂停")
}
