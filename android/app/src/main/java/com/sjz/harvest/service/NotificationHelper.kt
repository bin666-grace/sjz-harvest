package com.sjz.harvest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sjz.harvest.R
import com.sjz.harvest.core.logging.LogBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻通知栏控制面：展示运行状态，提供 暂停/继续/停止 三个动作。
 * 不依赖 UI 层的 Activity，避免服务层反向依赖 ui 包。
 */
object NotificationHelper {

    const val CHANNEL_ID = "sjz_automation"
    const val NOTIFICATION_ID = 1001

    private const val TAG = "Notification"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动化运行",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "特勤处自动收菜/补产的运行状态与控制按钮"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun update(context: Context, state: AutomationUiState) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, build(context, state))
        } catch (e: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS：静默失败，日志提示即可。
            LogBus.w(TAG, "无法更新通知（缺少通知权限）: ${e.message}")
        } catch (e: Throwable) {
            LogBus.w(TAG, "更新通知失败: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun build(context: Context, state: AutomationUiState): Notification {
        val errorText = state.error
        val title = when {
            errorText != null -> "特勤处收菜 · 已停止"
            state.paused -> "特勤处收菜 · 已暂停"
            state.running -> "特勤处收菜 · 运行中"
            else -> "特勤处收菜"
        }

        val text = when {
            errorText != null -> errorText
            state.paused -> "已暂停，等待继续"
            state.message.isNotBlank() -> state.message
            else -> state.phase
        }

        val body = buildString {
            append(state.phase)
            if (state.message.isNotBlank()) {
                append(" · ")
                append(state.message)
            }
            state.nextWakeAtMs?.let { wakeAt ->
                if (wakeAt > System.currentTimeMillis() && !state.paused) {
                    append("\n下次检查：")
                    append(formatTime(wakeAt))
                }
            }
            if (state.statuses.isNotEmpty()) {
                state.statuses.values.forEach { line ->
                    append('\n')
                    append(line)
                }
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(state.running || state.paused)
            .setAutoCancel(false)
            .setShowWhen(false)

        if (state.running || state.paused) {
            if (state.paused) {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "继续",
                    broadcast(context, ControlReceiver.ACTION_RESUME, REQUEST_RESUME),
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "暂停",
                    broadcast(context, ControlReceiver.ACTION_PAUSE, REQUEST_PAUSE),
                )
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                broadcast(context, ControlReceiver.ACTION_STOP, REQUEST_STOP),
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(contentIntent)
        }

        return builder.build()
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ControlReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatTime(timeMs: Long): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timeMs))
    }

    private const val REQUEST_OPEN = 1
    private const val REQUEST_PAUSE = 10
    private const val REQUEST_RESUME = 11
    private const val REQUEST_STOP = 12
}
