package com.sjz.harvest.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sjz.harvest.core.logging.LogBus

/**
 * 通知栏按钮的广播接收器。只做转发，所有状态机逻辑在 AutomationController。
 * Receiver 在 Manifest 中声明为 exported=false，PendingIntent 显式指向本类。
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        LogBus.i(TAG, "收到通知动作: $action")
        when (action) {
            ACTION_PAUSE -> AutomationController.pause()
            ACTION_RESUME -> AutomationController.resume()
            ACTION_STOP -> AutomationController.stop()
            else -> LogBus.w(TAG, "未知动作: $action")
        }
    }

    companion object {
        private const val TAG = "ControlReceiver"

        const val ACTION_PAUSE = "com.sjz.harvest.action.PAUSE"
        const val ACTION_RESUME = "com.sjz.harvest.action.RESUME"
        const val ACTION_STOP = "com.sjz.harvest.action.STOP"
    }
}
