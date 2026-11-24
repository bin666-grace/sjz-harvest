package com.sjz.harvest

import android.app.Application
import com.sjz.harvest.core.config.ServiceLocator
import com.sjz.harvest.core.logging.LogBus
import com.sjz.harvest.service.NotificationHelper

/**
 * 进程入口：初始化服务定位器、日志与通知渠道。
 * 不做耗时 I/O；配置在第一次使用时按需从 assets 复制。
 */
class SjzApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        LogBus.install(this, DEFAULT_LOG_LINES)
        NotificationHelper.createChannel(this)
        LogBus.i(TAG, "应用启动")
    }

    companion object {
        private const val TAG = "SjzApp"
        private const val DEFAULT_LOG_LINES = 300
    }
}
