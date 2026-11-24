package com.sjz.harvest.service

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.sjz.harvest.capture.AccessibilityScreenSource
import com.sjz.harvest.core.config.AppConfig
import com.sjz.harvest.core.config.Plan
import com.sjz.harvest.core.config.ServiceLocator
import com.sjz.harvest.core.logging.LogBus
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.DeptStatus
import com.sjz.harvest.core.model.SjzException
import com.sjz.harvest.game.CraftingController
import com.sjz.harvest.game.ForegroundGuard
import com.sjz.harvest.game.HarvestFlow
import com.sjz.harvest.game.ObserverImpl
import com.sjz.harvest.input.DryRunInput
import com.sjz.harvest.input.InputController
import com.sjz.harvest.input.InputFactory
import com.sjz.harvest.plan.PlanStore
import com.sjz.harvest.vision.MlKitOcrEngine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自动化编排器（单例）。
 *
 * 生命周期：
 * start -> 加载配置 -> 构建 source/ocr/input/observer/crafter/planStore/flow
 *       -> 常驻通知 -> loop { 检查暂停/限制/前台 -> flow.runCycle() -> delay(nextWake) }
 * pause -> 请求暂停，当前轮结束后阻塞在轮次之间
 * resume-> 解除阻塞
 * stop  -> 取消 Job（不再发新点击），回收 OCR/输入/截屏资源
 *
 * 所有异常都在此层收敛为 AutomationUiState，UI 和通知栏只读状态。
 */
object AutomationController {

    private const val TAG = "Automation"
    private const val UI_LOG_LINES = 200
    private const val MAX_WAKE_SEC = 86_400L
    private const val ACCESSIBILITY_CONNECT_WAIT_MS = 3_000L
    private const val FOREGROUND_POLL_MS = 500L

    /**
     * 读不到前台包名时的最短重试窗口。服务刚连接、窗口切换瞬间都可能读到 null；
     * 此时必须等待而不能「依赖页面锚点」直接开跑。
     */
    private const val FOREGROUND_NULL_RETRY_MS = 2_000L

    /** 业务层每次截屏/点击前的短校验窗口（包名读取偶发为 null 时容忍）。 */
    private const val FOREGROUND_CONFIRM_WINDOW_MS = 1_500L

    /** 周期之间用户切走游戏时，最多等待多久再停止（10 分钟）。 */
    private const val FOREGROUND_RESUME_WAIT_MS = 10 * 60_000L

    /** 计划要求立即重试但本轮没有任何动作时的防忙等间隔。 */
    private const val STUCK_BACKOFF_SEC = 5L

    private val _state = MutableStateFlow(AutomationUiState())
    val state: StateFlow<AutomationUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pausedFlow = MutableStateFlow(false)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var userStopped: Boolean = false

    // ------------------------------------------------------------------
    // 外部控制
    // ------------------------------------------------------------------

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) {
            LogBus.w(TAG, "自动化已在运行，忽略重复 start")
            return
        }
        val ctx = context.applicationContext
        ServiceLocator.init(ctx)
        NotificationHelper.createChannel(ctx)

        appContext = ctx
        userStopped = false
        pausedFlow.value = false
        _state.value = AutomationUiState(
            running = true,
            phase = "启动中",
            message = "正在初始化…",
            logs = LogBus.lines.value.takeLast(UI_LOG_LINES),
        )
        NotificationHelper.update(ctx, _state.value)
        // 进入前台服务：否则本进程会被系统当作后台缓存应用，在 MIUI 等机型上
        // 于游戏切前台后被冻结，恢复时截到的可能是本 App 自己的界面。
        try {
            AutomationForegroundService.start(ctx)
        } catch (error: Throwable) {
            LogBus.w(TAG, "启动前台服务失败（自动化继续，但可能被系统冻结）: ${error.message}")
        }
        LogBus.i(TAG, "请求启动自动化")

        job = scope.launch {
            runSession(ctx)
        }
    }

    @Synchronized
    fun pause() {
        if (job?.isActive != true) return
        if (pausedFlow.value) return
        pausedFlow.value = true
        publish(appContext) {
            it.copy(paused = true, phase = "暂停中", message = "本轮结束后暂停")
        }
        LogBus.i(TAG, "已请求暂停（当前轮次结束后生效）")
    }

    @Synchronized
    fun resume() {
        if (job?.isActive != true) return
        if (!pausedFlow.value) return
        pausedFlow.value = false
        publish(appContext) {
            it.copy(paused = false, phase = "运行中", message = "继续运行")
        }
        LogBus.i(TAG, "继续运行")
    }

    @Synchronized
    fun stop() {
        val ctx = appContext
        val running = job?.isActive == true
        if (!running) {
            userStopped = false
            pausedFlow.value = false
            publish(ctx) {
                it.copy(
                    running = false,
                    paused = false,
                    phase = "空闲",
                    message = "",
                    error = null,
                    nextWakeAtMs = null,
                )
            }
            if (ctx != null) {
                NotificationHelper.cancel(ctx)
                AutomationForegroundService.stop(ctx)
            }
            return
        }

        userStopped = true
        pausedFlow.value = false
        publish(ctx) {
            it.copy(
                running = true,
                paused = false,
                phase = "停止中",
                message = "正在停止…",
                nextWakeAtMs = null,
            )
        }
        LogBus.i(TAG, "用户请求停止")
        // cancel 后所有挂起点立即抛出 CancellationException，不会再发出新的点击。
        job?.cancel()
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    private suspend fun runSession(context: Context) {
        var terminal = false
        var source: AccessibilityScreenSource? = null
        var input: InputController? = null
        var ocr: MlKitOcrEngine? = null
        var config: AppConfig? = null
        val startedAt = SystemClock.elapsedRealtime()

        try {
            publish(context) { it.copy(phase = "初始化", message = "加载配置…") }
            val appConfig = ServiceLocator.appConfig()
            config = appConfig
            LogBus.install(context, appConfig.logging.maxLines)

            val profile = ServiceLocator.profile()
            val catalog = ServiceLocator.catalogIndex()
            val plan = ServiceLocator.loadPlan()
            LogBus.i(
                TAG,
                "配置已加载：game=${appConfig.gamePackage}, backend=${appConfig.input.backend}, " +
                    "dryRun=${appConfig.safety.dryRun}",
            )

            publish(context) { it.copy(phase = "初始化", message = "等待无障碍服务…") }
            val service = withTimeoutOrNull(ACCESSIBILITY_CONNECT_WAIT_MS) {
                SjzAccessibilityService.instance.first { it != null }
            } ?: throw SjzException.AccessibilityServiceUnavailable()

            val screenSource = AccessibilityScreenSource(
                serviceProvider = { SjzAccessibilityService.instance.value },
                config = appConfig.capture,
                logger = LogBus,
            )
            source = screenSource

            val screenSize = screenSource.screenSize().let { size ->
                if (size.first > 0 && size.second > 0) size else displaySize(context)
            }
            LogBus.i(TAG, "屏幕尺寸: ${screenSize.first}x${screenSize.second}")

            val ocrEngine = MlKitOcrEngine(LogBus, appConfig.timing.ocrRetries)
            ocr = ocrEngine

            val inputController = if (appConfig.safety.dryRun) {
                LogBus.i(TAG, "dry-run 已开启：只识别、不点击")
                DryRunInput(LogBus)
            } else {
                InputFactory.create(
                    context = context,
                    config = appConfig.input,
                    service = service,
                    screenSize = screenSize,
                    logger = LogBus,
                    requiredPackage = appConfig.gamePackage,
                )
            }
            input = inputController
            LogBus.i(TAG, "输入后端: ${inputController.backendName()}")

            // 周期开始/每次截屏/每次点击前都校验前台包名：
            // 用户可能中途切回本 App 看日志，若不校验会把 App 界面当游戏。
            val foregroundGuard = if (appConfig.safety.requireGameForeground) {
                val expectedPackage = appConfig.gamePackage
                ForegroundGuard {
                    // 游戏窗口在部分模拟器上偶尔读不到包名（rootInActiveWindow
                    // 返回 null）；给一个很短的确认窗口，而不是立刻判定「不在前台」。
                    val deadline = SystemClock.elapsedRealtime() +
                        FOREGROUND_CONFIRM_WINDOW_MS
                    while (true) {
                        val currentService = SjzAccessibilityService.instance.value
                            ?: throw SjzException.AccessibilityServiceUnavailable()
                        val foreground = currentService.foregroundPackage()
                        if (foreground == expectedPackage) return@ForegroundGuard
                        if (SystemClock.elapsedRealtime() >= deadline) {
                            throw SjzException.GameNotForeground(foreground)
                        }
                        delay(FOREGROUND_POLL_MS)
                    }
                }
            } else {
                ForegroundGuard.ALWAYS_OK
            }

            val observer = ObserverImpl(
                source = screenSource,
                ocr = ocrEngine,
                profile = profile,
                logger = LogBus,
                foregroundGuard = foregroundGuard,
                dumpOcrText = appConfig.logging.dumpOcrText,
            )
            val crafter = CraftingController(
                screenSource,
                ocrEngine,
                inputController,
                profile,
                catalog,
                appConfig.timing,
                appConfig.safety,
                LogBus,
                foregroundGuard,
            )
            val planStore = PlanStore(
                plan,
                { updated: Plan -> ServiceLocator.configRepository().savePlan(updated) },
                LogBus,
            )
            val flow = HarvestFlow(
                observer,
                crafter,
                planStore,
                profile,
                appConfig.timing,
                appConfig.safety,
                LogBus,
            )

            publish(context) {
                it.copy(
                    running = true,
                    phase = "运行中",
                    message = "已启动",
                    cycleCount = 0,
                    error = null,
                )
            }

            val maxCycles = appConfig.safety.maxCycles.coerceAtLeast(0)
            val maxRuntimeMs = if (appConfig.safety.maxRuntimeMinutes > 0) {
                appConfig.safety.maxRuntimeMinutes.toLong() * 60_000L
            } else {
                Long.MAX_VALUE
            }
            val foregroundGraceMs =
                if (appConfig.safety.requireGameForeground) {
                    // 用户通常在本 App 里按「开始」后再切到游戏，留足切换时间。
                    appConfig.timing.pageTimeoutMs.coerceAtLeast(30_000L)
                } else {
                    0L
                }

            var firstCycle = true
            var cycleCount = 0

            coroutineScope {
                // 无障碍服务被系统关闭时立即停止，避免长时间空转。
                val serviceWatcher = launch {
                    SjzAccessibilityService.instance.first { it == null }
                    throw SjzException.AccessibilityServiceUnavailable()
                }

                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        awaitResume(context)

                        if (maxCycles > 0 && cycleCount >= maxCycles) {
                            throw SjzException.SafetyLimitReached("已达到最大轮次 $cycleCount")
                        }
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        if (elapsed >= maxRuntimeMs) {
                            throw SjzException.SafetyLimitReached(
                                "已达到最长运行时间 ${appConfig.safety.maxRuntimeMinutes} 分钟",
                            )
                        }

                        if (appConfig.safety.requireGameForeground) {
                            awaitGameForeground(
                                context = context,
                                appConfig = appConfig,
                                // 首轮等用户切到游戏；之后若用户临时切走，
                                // 也给一个等待窗口再决定是否停止。
                                waitMs = if (firstCycle) {
                                    foregroundGraceMs
                                } else {
                                    FOREGROUND_RESUME_WAIT_MS
                                },
                            )
                        }
                        firstCycle = false

                        cycleCount++
                        publish(context) {
                            it.copy(
                                cycleCount = cycleCount,
                                phase = "第 $cycleCount 轮",
                                message = "截屏识别中…",
                                nextWakeAtMs = null,
                            )
                        }
                        LogBus.i(TAG, "开始第 $cycleCount 轮")

                        val result = flow.runCycle()
                        result.actions.take(8).forEach { action ->
                            LogBus.d(TAG, "动作 ${action.kind}: ${action.detail}")
                        }

                        if (result.stopped) {
                            terminal = true
                            val stopMessage = result.error?.takeIf { it.isNotBlank() } ?: "流程要求停止"
                            publish(context) {
                                it.copy(
                                    running = false,
                                    paused = false,
                                    phase = "已停止",
                                    message = stopMessage,
                                    error = stopMessage,
                                    nextWakeAtMs = null,
                                )
                            }
                            LogBus.w(TAG, "流程要求停止: $stopMessage")
                            break
                        }

                        val requestedWake = result.nextWakeSec.coerceIn(0L, MAX_WAKE_SEC)
                        val actionCount = result.actions.size
                        val wakeSec = when {
                            requestedWake > 0L -> requestedWake
                            // 本轮没有任何动作却要求立即重试，说明可能卡在缺材料/找不到物品，
                            // 加一点退避，避免高频 OCR 空转。
                            actionCount == 0 -> STUCK_BACKOFF_SEC
                            else -> 1L
                        }
                        val summary = result.error?.takeIf { it.isNotBlank() }
                            ?: "本轮完成（$actionCount 个动作），${wakeSec}s 后检查"
                        val nextWakeAtMs = System.currentTimeMillis() + wakeSec * 1_000L
                        publish(context) {
                            it.copy(
                                cycleCount = cycleCount,
                                phase = "等待下一轮",
                                message = summary,
                                statuses = formatStatuses(result.statuses),
                                nextWakeAtMs = nextWakeAtMs,
                                error = null,
                            )
                        }
                        LogBus.i(TAG, "第 $cycleCount 轮完成：actions=$actionCount, wake=${wakeSec}s")

                        if (maxCycles > 0 && cycleCount >= maxCycles) {
                            throw SjzException.SafetyLimitReached("已达到最大轮次 $cycleCount")
                        }
                        val remainingRuntime =
                            maxRuntimeMs - (SystemClock.elapsedRealtime() - startedAt)
                        if (remainingRuntime <= 0L) {
                            throw SjzException.SafetyLimitReached(
                                "已达到最长运行时间 ${appConfig.safety.maxRuntimeMinutes} 分钟",
                            )
                        }
                        val wakeMs = minOf(wakeSec * 1_000L, remainingRuntime)
                        delayInterruptible(context, wakeMs)
                    }
                } finally {
                    serviceWatcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            terminal = true
            val saveScreenshot = e is SjzException.PageNotRecognized || e !is SjzException
            if (saveScreenshot) {
                maybeSaveFailureScreenshot(
                    context = context,
                    source = source,
                    enabled = config?.logging?.saveFailureScreenshots == true,
                )
            }
            handleTerminalError(context, e)
        } finally {
            try {
                ocr?.close()
            } catch (t: Throwable) {
                LogBus.w(TAG, "关闭 OCR 失败: ${t.message}")
            }
            try {
                input?.close()
            } catch (t: Throwable) {
                LogBus.w(TAG, "关闭输入后端失败: ${t.message}")
            }
            try {
                source?.close()
            } catch (t: Throwable) {
                LogBus.w(TAG, "关闭截屏源失败: ${t.message}")
            }
            // 与 start/pause/resume/stop 使用同一把对象锁，避免「停止中」覆盖最终状态。
            synchronized(this) {
                pausedFlow.value = false
                if (!terminal || userStopped) {
                    val finalMessage = if (userStopped) "用户停止" else _state.value.message
                    publish(context) {
                        it.copy(
                            running = false,
                            paused = false,
                            phase = "已停止",
                            message = finalMessage,
                            error = if (userStopped) null else it.error,
                            nextWakeAtMs = null,
                        )
                    }
                }
                if (userStopped) {
                    NotificationHelper.cancel(context)
                } else {
                    NotificationHelper.update(context, _state.value)
                }
                // DETACH 保留最终状态通知；已在上面决定是否 cancel。
                AutomationForegroundService.stop(context)
                job = null
            }
            LogBus.i(TAG, "自动化会话结束")
        }
    }

    // ------------------------------------------------------------------
    // 暂停 / 前台 / 延时
    // ------------------------------------------------------------------

    private suspend fun awaitResume(context: Context) {
        if (!pausedFlow.value) return
        publish(context) {
            it.copy(
                paused = true,
                phase = "已暂停",
                message = "已暂停，点击通知栏“继续”恢复",
            )
        }
        pausedFlow.first { !it }
        publish(context) {
            it.copy(paused = false, phase = "运行中", message = "继续运行")
        }
    }

    private suspend fun delayInterruptible(context: Context, totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            if (pausedFlow.value) {
                // 暂停期间不消耗剩余等待时间；恢复后继续倒计时。
                awaitResume(context)
                continue
            }
            val step = minOf(remaining, FOREGROUND_POLL_MS)
            delay(step)
            remaining -= step
        }
    }

    private suspend fun awaitGameForeground(
        context: Context,
        appConfig: AppConfig,
        waitMs: Long,
    ) {
        val gamePackage = appConfig.gamePackage
        var deadline = SystemClock.elapsedRealtime() + waitMs
        // 包名可读性差时单独给一个短窗口，不能沿用整轮等待时间，
        // 也不能像旧实现那样直接放行。
        var nullDeadline = SystemClock.elapsedRealtime() +
            maxOf(waitMs, FOREGROUND_NULL_RETRY_MS)
        var waitingNotified = false

        while (true) {
            currentCoroutineContext().ensureActive()
            if (pausedFlow.value) {
                val pausedAt = SystemClock.elapsedRealtime()
                awaitResume(context)
                val pausedFor = SystemClock.elapsedRealtime() - pausedAt
                deadline += pausedFor
                nullDeadline += pausedFor
                continue
            }

            val service = SjzAccessibilityService.instance.value
                ?: throw SjzException.AccessibilityServiceUnavailable()
            val foreground = service.foregroundPackage()

            if (foreground == gamePackage) {
                if (waitingNotified) LogBus.i(TAG, "游戏已切到前台")
                return
            }

            if (foreground == null) {
                // 绝不把「读不到包名」当成放行：服务刚连接/窗口切换时读到 null，
                // 曾经导致 OCR 把本 App 自己的总览界面当成游戏并开始点击。
                if (SystemClock.elapsedRealtime() >= nullDeadline) {
                    throw SjzException.GameNotForeground("unknown")
                }
                if (!waitingNotified) {
                    waitingNotified = true
                    publish(context) {
                        it.copy(
                            phase = "等待游戏",
                            message = "无法识别当前前台应用，请确认游戏已在前台",
                        )
                    }
                    LogBus.w(TAG, "无法获取前台包名，等待识别（不执行任何动作）")
                }
                delay(FOREGROUND_POLL_MS)
                continue
            }

            if (SystemClock.elapsedRealtime() >= deadline) {
                throw SjzException.GameNotForeground(foreground)
            }
            if (!waitingNotified) {
                waitingNotified = true
                val current = foreground
                publish(context) {
                    it.copy(
                        phase = "等待游戏",
                        message = "请切换到游戏前台（当前：$current）",
                    )
                }
                LogBus.i(TAG, "等待游戏切到前台，当前: $foreground")
            }
            delay(FOREGROUND_POLL_MS)
        }
    }

    // ------------------------------------------------------------------
    // 状态 / 异常 / 辅助
    // ------------------------------------------------------------------

    private fun publish(
        context: Context?,
        transform: (AutomationUiState) -> AutomationUiState,
    ) {
        val next = _state.updateAndGet { current ->
            transform(current.copy(logs = LogBus.lines.value.takeLast(UI_LOG_LINES)))
        }
        if (context != null) NotificationHelper.update(context, next)
    }

    private fun handleTerminalError(context: Context, error: Throwable) {
        val message = when (error) {
            is SjzException.AccessibilityServiceUnavailable ->
                "无障碍服务未连接，请在系统设置中开启本应用的无障碍服务"
            is SjzException.GameNotForeground ->
                "游戏不在前台（${error.packageName ?: "未知"}），已停止"
            is SjzException.PageNotRecognized ->
                "无法识别游戏页面，已停止：${error.message}"
            is SjzException.CaptureFailed ->
                "截屏失败，已停止：${error.message}"
            is SjzException.InputUnavailable ->
                "输入不可用，已停止：${error.message}"
            is SjzException.ConfigError ->
                "配置错误：${error.message}"
            is SjzException.PlanError ->
                "生产计划错误：${error.message}"
            is SjzException.SafetyLimitReached ->
                error.message ?: "达到安全限制"
            is SjzException.StoppedByUser -> {
                userStopped = true
                "用户停止"
            }
            is SjzException.PausedByUser -> {
                userStopped = true
                "用户暂停"
            }
            is SjzException -> error.message ?: "自动化异常"
            else -> "未知异常：${error.message ?: error.javaClass.simpleName}"
        }

        val isNormalStop = error is SjzException.SafetyLimitReached ||
            error is SjzException.StoppedByUser ||
            error is SjzException.PausedByUser
        if (isNormalStop) {
            LogBus.i(TAG, message)
        } else {
            LogBus.e(TAG, message, error)
        }

        publish(context) {
            it.copy(
                running = false,
                paused = false,
                phase = "已停止",
                message = message,
                error = if (isNormalStop) null else message,
                nextWakeAtMs = null,
            )
        }
    }

    private suspend fun maybeSaveFailureScreenshot(
        context: Context,
        source: AccessibilityScreenSource?,
        enabled: Boolean,
    ) {
        if (!enabled || source == null) return
        try {
            val frame = source.capture()
            try {
                val dir = File(context.filesDir, "debug")
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("无法创建 debug 目录")
                }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val file = File(dir, "failure_$stamp.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { output ->
                        frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                }
                LogBus.w(TAG, "失败截图已保存: ${file.absolutePath}")
            } finally {
                frame.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LogBus.w(TAG, "保存失败截图失败: ${t.message}")
        }
    }

    private fun displaySize(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun formatStatuses(statuses: Map<Dept, DeptStatus>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (dept in Dept.entries) {
            val status = statuses[dept] ?: continue
            result[dept.key] = "${dept.label} · ${describeStatus(status)}"
        }
        return result
    }

    private fun describeStatus(status: DeptStatus): String = when (status.state) {
        DeptState.IDLE -> "空闲"
        DeptState.CRAFTING -> "制造中 ${formatRemaining(status.remainingSec)}"
        DeptState.DONE -> "待收取"
        DeptState.UNKNOWN -> "未知"
    }

    private fun formatRemaining(seconds: Int?): String {
        val sec = (seconds ?: 0).coerceAtLeast(0)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            sec / 3600,
            (sec % 3600) / 60,
            sec % 60,
        )
    }
}
