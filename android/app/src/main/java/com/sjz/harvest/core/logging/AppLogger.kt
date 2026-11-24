package com.sjz.harvest.core.logging

/**
 * 业务层只依赖这个接口。Android 端用 LogBus 实现：
 * 同时写 Logcat、内存环形缓冲（UI）和文件。
 */
interface AppLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object NoopLogger : AppLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
