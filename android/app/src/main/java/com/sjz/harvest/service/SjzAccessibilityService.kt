package com.sjz.harvest.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.sjz.harvest.core.logging.LogBus
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.SjzException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 截屏失败时携带的错误码。ScreenSource 用它区分「截屏太频繁」与其他错误，
 * 前者固定等待 450ms 重试，后者按 capture.retries 重试。
 */
class ScreenshotFailureException(
    val code: Int,
    message: String,
) : Exception(message) {
    val intervalTooShort: Boolean
        get() = code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT

    /** ERROR_TAKE_SCREENSHOT_SECURE_WINDOW = 5（API 34 新增；这里用数值避免运行时字段差异） */
    val secureWindow: Boolean
        get() = code == ERROR_CODE_SECURE_WINDOW

    companion object {
        const val ERROR_CODE_SECURE_WINDOW = 5
    }
}

/**
 * 系统绑定的无障碍服务，常驻进程，提供三件事：
 * 1. API 30+ takeScreenshot 截屏（HardwareBuffer -> 软件 Bitmap）；
 * 2. 无障碍手势 tap/swipe/back；
 * 3. 当前前台包名。
 *
 * 注意：业务层不应直接依赖本类；AccessibilityScreenSource / AccessibilityGestureInput
 * 会把它包装成接口。本类只做平台能力，不做任何业务判断。
 */
class SjzAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenshotExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sjz-screenshot").apply { isDaemon = true }
        }

    /** 串行化截屏，并保证与上一次请求至少间隔 400ms（系统约 333ms 限速）。 */
    private val screenshotMutex = Mutex()

    @Volatile
    private var lastScreenshotAtMs: Long = 0L

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var lastForegroundPackage: String? = null

    @Volatile
    private var lastForegroundPackageAtMs: Long = 0L

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        lastForegroundPackage = null
        lastForegroundPackageAtMs = 0L
        _instance.value = this
        LogBus.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        // 只用「窗口切换」更新前台包名。TYPE_WINDOW_CONTENT_CHANGED 连后台窗口
        // 也会发（例如微信/系统设置），用它会把手游残留的包名污染成后台应用。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPackage = packageName
            lastForegroundPackageAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onInterrupt() {
        // 系统要求实现；本服务不响应中断事件。
    }

    override fun onDestroy() {
        connected = false
        lastForegroundPackage = null
        lastForegroundPackageAtMs = 0L
        _instance.compareAndSet(this, null)
        screenshotExecutor.shutdownNow()
        LogBus.i(TAG, "无障碍服务已断开")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 平台能力：截屏
    // ------------------------------------------------------------------

    /**
     * 截取当前全屏画面。返回 ARGB_8888 软件位图，调用方负责 recycle。
     * 失败时抛 [SjzException.CaptureFailed]；其 cause 可能是
     * [ScreenshotFailureException]（含系统错误码）。
     */
    suspend fun captureBitmap(): Bitmap {
        if (!connected) throw SjzException.AccessibilityServiceUnavailable()
        return screenshotMutex.withLock {
            val elapsed = SystemClock.elapsedRealtime() - lastScreenshotAtMs
            if (lastScreenshotAtMs > 0L && elapsed < MIN_SCREENSHOT_INTERVAL_MS) {
                delay(MIN_SCREENSHOT_INTERVAL_MS - elapsed)
            }
            try {
                val bitmap = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                    takeScreenshotOnce()
                }
                if (bitmap == null) {
                    throw SjzException.CaptureFailed("截屏超时（${SCREENSHOT_TIMEOUT_MS}ms 未回调）")
                }
                bitmap
            } finally {
                lastScreenshotAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private suspend fun takeScreenshotOnce(): Bitmap = suspendCancellableCoroutine<Bitmap> { cont ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var software: Bitmap? = null
                try {
                    val hardwareBuffer = result.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                            ?: throw SjzException.CaptureFailed("截屏 HardwareBuffer 转换失败")
                        try {
                            software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                ?: throw SjzException.CaptureFailed("截屏软件位图创建失败")
                        } finally {
                            if (!wrapped.isRecycled) wrapped.recycle()
                        }
                    } finally {
                        hardwareBuffer.close()
                    }
                    val bitmap = software
                        ?: throw SjzException.CaptureFailed("截屏位图为空")
                    if (cont.isActive) {
                        cont.resume(bitmap)
                    } else if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (t: Throwable) {
                    software?.let { if (!it.isRecycled) it.recycle() }
                    if (cont.isActive) {
                        cont.resumeWithException(
                            if (t is SjzException) t
                            else SjzException.CaptureFailed("截屏处理失败: ${t.message}", t),
                        )
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (!cont.isActive) return
                val message = screenshotErrorMessage(errorCode)
                cont.resumeWithException(
                    SjzException.CaptureFailed(
                        message,
                        ScreenshotFailureException(errorCode, message),
                    ),
                )
            }
        }

        try {
            // 必须在主线程调用 takeScreenshot；回调通过 screenshotExecutor 回到后台线程。
            mainHandler.post {
                if (!connected) {
                    if (cont.isActive) {
                        cont.resumeWithException(SjzException.AccessibilityServiceUnavailable())
                    }
                    return@post
                }
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, callback)
                } catch (t: Throwable) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            SjzException.CaptureFailed("发起截屏失败: ${t.message}", t),
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            if (cont.isActive) {
                cont.resumeWithException(SjzException.CaptureFailed("发起截屏失败: ${t.message}", t))
            }
        }
    }

    // ------------------------------------------------------------------
    // 平台能力：手势
    // ------------------------------------------------------------------

    suspend fun tap(point: PointI) {
        tapWithHold(point, DEFAULT_TAP_HOLD_MS)
    }

    /** 供 AccessibilityGestureInput 使用，可指定按压时长。 */
    suspend fun tapWithHold(point: PointI, holdMs: Long) {
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
            // 极短路径比零长度路径兼容性更好（部分设备对纯 moveTo 不识别）。
            lineTo(point.x.toFloat() + 1f, point.y.toFloat() + 1f)
        }
        val duration = holdMs.coerceIn(1L, MAX_GESTURE_DURATION_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        dispatchGestureSuspend(
            GestureDescription.Builder().addStroke(stroke).build(),
            "tap(${point.x},${point.y})",
        )
    }

    suspend fun swipe(from: PointI, to: PointI, durationMs: Long) {
        val path = Path().apply {
            moveTo(from.x.toFloat(), from.y.toFloat())
            lineTo(to.x.toFloat(), to.y.toFloat())
        }
        val duration = durationMs.coerceIn(1L, MAX_GESTURE_DURATION_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        dispatchGestureSuspend(
            GestureDescription.Builder().addStroke(stroke).build(),
            "swipe(${from.x},${from.y} -> ${to.x},${to.y})",
        )
    }

    suspend fun back() {
        if (!connected) throw SjzException.AccessibilityServiceUnavailable()
        val ok = withContext(Dispatchers.Main.immediate) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        if (!ok) throw SjzException.InputUnavailable("performGlobalAction(BACK) 失败")
    }

    private suspend fun dispatchGestureSuspend(
        gesture: GestureDescription,
        description: String,
    ) {
        val completed = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                mainHandler.post {
                    if (!connected) {
                        if (cont.isActive) {
                            cont.resumeWithException(SjzException.AccessibilityServiceUnavailable())
                        }
                        return@post
                    }
                    val accepted = try {
                        dispatchGesture(
                            gesture,
                            object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    if (cont.isActive) cont.resume(true)
                                }

                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    if (cont.isActive) cont.resume(false)
                                }
                            },
                            null,
                        )
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                        false
                    }
                    if (!accepted && cont.isActive) cont.resume(false)
                }
            }
        }
        if (completed != true) {
            throw SjzException.InputUnavailable("无障碍手势未完成: $description")
        }
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    fun foregroundPackage(): String? {
        // 优先读取当前活动窗口；部分游戏窗口会返回 null。
        val active = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
        if (active != null) {
            // 活动窗口是权威来源，同时刷新事件缓存，减少后续读取失败的概率。
            lastForegroundPackage = active
            lastForegroundPackageAtMs = SystemClock.elapsedRealtime()
            return active
        }
        // 兜底 1：交互窗口列表。游戏窗口的 root 节点常为空，但窗口本身
        // 仍能被列出；取「有焦点」的窗口包名，比单纯依赖事件缓存可靠。
        val focused = try {
            windows
                .firstOrNull { window -> window.isFocused && window.isActive }
                ?.root?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
        if (focused != null) {
            lastForegroundPackage = focused
            lastForegroundPackageAtMs = SystemClock.elapsedRealtime()
            return focused
        }
        // 兜底 2：最近一次窗口切换事件（3 秒内有效）。
        // 不要无限期沿用旧包名：曾出现「本 App 在屏幕上，缓存仍是上一款游戏」，
        // 自动化把自己的设置界面当成游戏总览并开始点击。超过窗口期一律视为未知。
        val ageMs = SystemClock.elapsedRealtime() - lastForegroundPackageAtMs
        val cached = lastForegroundPackage
        return if (cached != null && lastForegroundPackageAtMs > 0L &&
            ageMs <= FOREGROUND_CACHE_MAX_AGE_MS
        ) {
            cached
        } else {
            null
        }
    }

    /**
     * 当前显示器的真实尺寸（跟随屏幕旋转），不是本 App 窗口的尺寸。
     *
     * 本 App 固定竖屏，而游戏是横屏：自动化启动时 App 在前台，窗口尺寸是
     * 1220×2712；切到游戏后截图/OCR 是 2712×1220。若用窗口尺寸做点击范围
     * 校验，横坐标会被错误地截断到 1220，表现为「点了，但点错位置」。
     * 因此这里读取 default display 的实时 metrics；手势派发时再取一次。
     */
    fun screenSize(): Pair<Int, Int> {
        try {
            val displayManager = getSystemService(DisplayManager::class.java)
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels to metrics.heightPixels
                }
            }
        } catch (_: Throwable) {
            // 退化为窗口/资源尺寸
        }
        try {
            val windowManager = getSystemService(WindowManager::class.java)
            if (windowManager != null) {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            }
        } catch (_: Throwable) {
            // 退化为 resources.displayMetrics
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun screenshotErrorMessage(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "截屏过于频繁（系统限速）"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "无障碍服务没有截屏权限"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "无效的显示设备"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            "截屏内部错误"
        ScreenshotFailureException.ERROR_CODE_SECURE_WINDOW ->
            "安全窗口无法截屏（可能启用了 FLAG_SECURE）"
        else -> "截屏失败（错误码 $errorCode）"
    }

    companion object {
        private const val TAG = "SjzA11y"

        private const val MIN_SCREENSHOT_INTERVAL_MS = 400L
        private const val SCREENSHOT_TIMEOUT_MS = 8_000L
        private const val DEFAULT_TAP_HOLD_MS = 60L
        private const val GESTURE_TIMEOUT_MS = 10_000L
        private const val MAX_GESTURE_DURATION_MS = 60_000L

        /** 窗口事件缓存的前台包名最多可复用多久；超过即视为未知。 */
        private const val FOREGROUND_CACHE_MAX_AGE_MS = 3_000L

        private val _instance = MutableStateFlow<SjzAccessibilityService?>(null)

        /** 当前连接的服务实例；未连接时为 null。 */
        val instance: StateFlow<SjzAccessibilityService?> = _instance.asStateFlow()

        /** 无障碍服务是否已在系统设置中启用（不要求当前已连接）。 */
        fun isEnabled(context: Context): Boolean {
            if (_instance.value != null) return true
            val appContext = context.applicationContext
            val expected = ComponentName(appContext, SjzAccessibilityService::class.java)
            val enabledServices = try {
                Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
            } catch (_: Throwable) {
                ""
            }
            val matchedBySettings = enabledServices
                .split(':')
                .asSequence()
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { component ->
                    component.packageName == expected.packageName &&
                        component.className == expected.className
                }
            if (matchedBySettings) return true

            // 兜底：部分 OEM 的设置字符串格式不同，直接查询 AccessibilityManager。
            val manager = appContext.getSystemService(AccessibilityManager::class.java)
                ?: return false
            if (!manager.isEnabled) return false
            return try {
                manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                ).any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo
                    serviceInfo != null &&
                        serviceInfo.packageName == expected.packageName &&
                        serviceInfo.name.endsWith(SERVICE_CLASS_SIMPLE_NAME)
                }
            } catch (_: Throwable) {
                false
            }
        }

        /** 跳转系统无障碍设置页，方便用户开启服务。 */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.applicationContext.startActivity(intent)
            } catch (t: Throwable) {
                LogBus.w(TAG, "无法打开无障碍设置: ${t.message}")
            }
        }

        private const val SERVICE_CLASS_SIMPLE_NAME = "SjzAccessibilityService"
    }
}
