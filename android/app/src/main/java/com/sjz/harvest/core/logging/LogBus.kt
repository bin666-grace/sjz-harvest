package com.sjz.harvest.core.logging

import android.content.Context
import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AppLogger 的 Android 实现：
 * - 写 Logcat，便于 adb logcat 调试；
 * - 维护内存环形缓冲，通过 [lines] 暴露给 UI / 通知；
 * - 所有方法都可在任意线程调用。
 *
 * 注意：这里刻意不写文件。日志量不大，Logcat + 内存即可；
 * 需要持久化时由后续版本单独增加文件 sink。
 */
object LogBus : AppLogger {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** 最近若干行日志，新行追加在末尾。 */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile
    private var maxLines: Int = 300

    /**
     * 由 Application / UI 在启动时调用。可重复调用，
     * 后续调用会按新的 maxLines 裁剪已有日志。
     */
    fun install(@Suppress("UNUSED_PARAMETER") context: Context, maxLines: Int) {
        val limit = maxLines.coerceIn(MIN_LINES, MAX_LINES)
        this.maxLines = limit
        _lines.update { old ->
            if (old.size <= limit) old else old.takeLast(limit)
        }
    }

    override fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("W", tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
        val suffix = throwable?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()
        append("E", tag, message + suffix)
    }

    private fun append(level: String, tag: String, message: String) {
        val line = "${LocalTime.now().format(timeFormatter)} $level/$tag: $message"
        val limit = maxLines.coerceAtLeast(1)
        _lines.update { old ->
            if (old.size >= limit) {
                old.takeLast(limit - 1) + line
            } else {
                old + line
            }
        }
    }

    private const val MIN_LINES = 50
    private const val MAX_LINES = 5_000
}
