package com.sjz.harvest.input

import com.sjz.harvest.core.config.InputConfig
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.logging.NoopLogger
import com.sjz.harvest.core.model.PointI
import com.sjz.harvest.core.model.SjzException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 root shell 执行 `input tap/swipe/keyevent` 的输入后端。
 * 模拟器上通常比无障碍手势可靠。
 *
 * - 创建时执行 `su -c id`，输出包含 `uid=0` 才可用；
 * - 每条命令 3s 超时，退出码非 0 视为不可用；
 * - 点击坐标加 ±jitterPx 抖动并 clamp 到屏幕内（仅点击，滑动不加抖动）。
 */
class RootShellInput private constructor(
    private val screenSize: Pair<Int, Int>,
    private val config: InputConfig,
    private val logger: AppLogger,
    rootAvailable: Boolean,
) : InputController {

    @Volatile
    private var available: Boolean = rootAvailable

    @Volatile
    private var closed: Boolean = false

    override suspend fun tap(point: PointI) {
        ensureAvailable()
        val jitter = config.jitterPx.coerceAtLeast(0)
        val jx = if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0
        val jy = if (jitter > 0) Random.nextInt(-jitter, jitter + 1) else 0
        val target = clamp(point.offset(jx, jy))
        execute("input tap ${target.x} ${target.y}")
    }

    override suspend fun swipe(from: PointI, to: PointI, durationMs: Long) {
        ensureAvailable()
        val start = clamp(from)
        val end = clamp(to)
        val duration = durationMs.coerceAtLeast(1L)
        execute("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $duration")
    }

    override suspend fun back() {
        ensureAvailable()
        execute("input keyevent 4")
    }

    override fun isAvailable(): Boolean = available && !closed

    override fun backendName(): String = "root"

    override fun close() {
        closed = true
        available = false
    }

    private fun ensureAvailable() {
        if (!isAvailable()) {
            throw SjzException.InputUnavailable("root 输入不可用（su 未授权或已关闭）")
        }
    }

    private fun clamp(point: PointI): PointI {
        val width = screenSize.first
        val height = screenSize.second
        if (width <= 0 || height <= 0) return point
        return PointI(
            point.x.coerceIn(0, width - 1),
            point.y.coerceIn(0, height - 1),
        )
    }

    private suspend fun execute(command: String) {
        withContext(Dispatchers.IO) {
            logger.d(TAG, "su -c $command")
            var process: Process? = null
            try {
                val running = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                process = running
                val finished = running.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    running.destroyForcibly()
                    throw SjzException.InputUnavailable("root 命令超时（${COMMAND_TIMEOUT_MS}ms）: $command")
                }
                val output = running.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = running.exitValue()
                if (exitCode != 0) {
                    throw SjzException.InputUnavailable(
                        "root 命令失败（exit=$exitCode）: $command ${output.take(200)}",
                    )
                }
            } catch (e: SjzException.InputUnavailable) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SjzException.InputUnavailable("root 命令被中断: $command")
            } catch (e: Exception) {
                throw SjzException.InputUnavailable("root 命令执行失败: ${e.message}")
            } finally {
                process?.destroy()
            }
        }
    }

    companion object {
        private const val TAG = "RootInput"
        private const val COMMAND_TIMEOUT_MS = 3_000L

        /** 创建实例时检测 root；不可用时返回的对象 isAvailable() == false。 */
        suspend fun create(
            config: InputConfig,
            screenSize: Pair<Int, Int>,
            logger: AppLogger,
        ): RootShellInput = withContext(Dispatchers.IO) {
            val available = detectRoot(logger)
            if (available) {
                logger.i(TAG, "root 可用（su -c id = uid=0）")
            } else {
                logger.w(TAG, "root 不可用：su 未授权或设备未 root")
            }
            RootShellInput(screenSize, config, logger, available)
        }

        /** 只探测，不创建实例；供自检使用。 */
        suspend fun probe(logger: AppLogger = NoopLogger): Boolean =
            withContext(Dispatchers.IO) { detectRoot(logger) }

        private fun detectRoot(logger: AppLogger): Boolean {
            var process: Process? = null
            return try {
                val running = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                process = running
                val finished = running.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    running.destroyForcibly()
                    false
                } else {
                    val output = running.inputStream.bufferedReader().use { it.readText() }
                    running.exitValue() == 0 && output.contains("uid=0")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.w(TAG, "root 检测被中断")
                false
            } catch (e: Exception) {
                logger.d(TAG, "root 检测失败: ${e.message}")
                false
            } finally {
                process?.destroy()
            }
        }
    }
}
