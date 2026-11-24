package com.sjz.harvest.service

/**
 * UI / 通知栏共享的运行状态。AutomationController 用 StateFlow 暴露。
 * logs 是最近 N 行（新行追加在末尾）。
 */
data class AutomationUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val phase: String = "空闲",
    val message: String = "",
    val nextWakeAtMs: Long? = null,
    val cycleCount: Int = 0,
    /** dept.key -> "技术中心 · 制造中 01:23:45" 之类的可读文本 */
    val statuses: Map<String, String> = emptyMap(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
)
