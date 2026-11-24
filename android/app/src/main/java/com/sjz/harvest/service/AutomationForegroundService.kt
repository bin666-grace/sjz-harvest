package com.sjz.harvest.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.sjz.harvest.core.logging.LogBus

/**
 * 自动化会话的前台服务。
 *
 * 存在的唯一目的：让系统知道「本进程正在执行用户发起的长时间任务」，
 * 从而不把进程当成可回收/可冻结的后台缓存应用。
 *
 * 背景：早期实现只在 NotificationManager 里发常驻通知，进程仍处于后台状态。
 * 在 MIUI 等激进省电的系统上，游戏切到前台后本 App 进程会被冻结，
 * 表现为自动化卡住数分钟，用户切回 App 才恢复——恢复后截到的却是 App
 * 自己的界面，极易误判/误点。
 *
 * 通知内容仍由 [NotificationHelper] 按同一 ID 更新；
 * 服务停止时用 DETACH 保留最终状态通知（例如「已停止 / 错误原因」）。
 */
class AutomationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.createChannel(this)
        val notification = NotificationHelper.build(this, AutomationController.state.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须声明类型；specialUse 用于「用户发起的其他长任务」。
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
            LogBus.i(TAG, "自动化前台服务已启动")
        } catch (error: Throwable) {
            // 权限/系统限制导致无法进入前台时，自动化仍可继续（只是更可能被冻结）。
            LogBus.w(TAG, "进入前台失败: ${error.javaClass.simpleName}: ${error.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // DETACH：保留最后的运行/错误通知，由 NotificationHelper.cancel 决定何时清除。
        stopForeground(STOP_FOREGROUND_DETACH)
        LogBus.i(TAG, "自动化前台服务已停止")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutomationFgs"

        /** 必须在应用处于前台（用户刚点了开始/继续）时调用。 */
        fun start(context: Context) {
            val intent = Intent(context, AutomationForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationForegroundService::class.java))
        }
    }
}
