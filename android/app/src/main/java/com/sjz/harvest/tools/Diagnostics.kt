package com.sjz.harvest.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.sjz.harvest.R
import com.sjz.harvest.core.config.InputConfig
import com.sjz.harvest.core.config.ServiceLocator
import com.sjz.harvest.core.logging.LogBus
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.Geometry
import com.sjz.harvest.input.InputFactory
import com.sjz.harvest.service.SjzAccessibilityService
import com.sjz.harvest.vision.MlKitOcrEngine
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * 单条自检结果。ok=false 时 detail 应说明原因和下一步建议。
 */
data class DiagnosticItem(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

/**
 * 首次使用/故障排查时的自检：
 * 无障碍开关与连接、截屏（非全黑）、ML Kit OCR、root/无障碍/dry-run 输入后端、
 * 前台包名、profile/catalog/plan 加载、屏幕分辨率。
 *
 * 注意：在 App 内点自检时，前台包名通常显示本 App，这是正常的。
 */
object Diagnostics {

    suspend fun run(context: Context): List<DiagnosticItem> {
        val app = context.applicationContext
        val items = mutableListOf<DiagnosticItem>()

        // 1. app_config
        val configResult = runCatching { ServiceLocator.appConfig() }
        val config = configResult.getOrNull()
        configResult.fold(
            onSuccess = { loaded ->
                items += DiagnosticItem(
                    app.getString(R.string.diag_app_config),
                    true,
                    app.getString(
                        R.string.diag_config_ok_fmt,
                        loaded.gamePackage,
                        loaded.input.backend,
                    ),
                )
            },
            onFailure = { throwable ->
                items += DiagnosticItem(
                    app.getString(R.string.diag_app_config),
                    false,
                    app.getString(R.string.diag_config_failed_fmt, describe(throwable)),
                )
            },
        )

        // 2. 无障碍开关
        val enabled = runCatching { SjzAccessibilityService.isEnabled(app) }
            .getOrDefault(false)
        items += DiagnosticItem(
            app.getString(R.string.diag_access_enabled),
            enabled,
            app.getString(
                if (enabled) R.string.diag_detail_enabled else R.string.diag_detail_disabled,
            ),
        )

        // 3. 无障碍连接（开启后允许 3 秒连接时间）
        var service = SjzAccessibilityService.instance.value
        if (enabled && service == null) {
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (service == null && SystemClock.elapsedRealtime() < deadline) {
                delay(200)
                service = SjzAccessibilityService.instance.value
            }
        }
        items += DiagnosticItem(
            app.getString(R.string.diag_access_connected),
            service != null,
            app.getString(
                if (service != null) {
                    R.string.diag_detail_connected
                } else {
                    R.string.diag_detail_not_connected
                },
            ),
        )
        val connectedService = service

        // 4. 截屏 + 全黑检测
        val screenshot = if (connectedService == null) {
            items += DiagnosticItem(
                app.getString(R.string.diag_capture),
                false,
                app.getString(R.string.diag_detail_no_service),
            )
            null
        } else {
            runCatching { connectedService.captureBitmap() }.fold(
                onSuccess = { bitmap ->
                    // 采样失败时不阻塞其余自检项（1/1 视为无法判断，不误报全黑）。
                    val (nonBlack, total) = runCatching { sampleNonBlack(bitmap) }
                        .getOrDefault(1 to 1)
                    if (nonBlack == 0) {
                        items += DiagnosticItem(
                            app.getString(R.string.diag_capture),
                            false,
                            app.getString(
                                R.string.diag_capture_black,
                                bitmap.width,
                                bitmap.height,
                            ),
                        )
                    } else {
                        items += DiagnosticItem(
                            app.getString(R.string.diag_capture),
                            true,
                            app.getString(
                                R.string.diag_capture_ok_fmt,
                                bitmap.width,
                                bitmap.height,
                                nonBlack,
                                total,
                            ),
                        )
                    }
                    bitmap
                },
                onFailure = { throwable ->
                    items += DiagnosticItem(
                        app.getString(R.string.diag_capture),
                        false,
                        app.getString(R.string.diag_capture_failed_fmt, describe(throwable)),
                    )
                    null
                },
            )
        }

        // 5. OCR
        if (screenshot == null) {
            items += DiagnosticItem(
                app.getString(R.string.diag_ocr),
                false,
                app.getString(R.string.diag_ocr_empty),
            )
        } else {
            val engine = MlKitOcrEngine(LogBus, retries = 1)
            val softwareCopy = copyToSoftware(screenshot)
            try {
                val ocrBitmap = softwareCopy ?: screenshot
                val result = engine.recognize(ocrBitmap, "diagnostics")
                if (result.elements.isEmpty()) {
                    items += DiagnosticItem(
                        app.getString(R.string.diag_ocr),
                        false,
                        app.getString(R.string.diag_ocr_empty),
                    )
                } else {
                    val preview = result.elements
                        .take(3)
                        .joinToString(" / ") { it.text }
                        .take(80)
                    items += DiagnosticItem(
                        app.getString(R.string.diag_ocr),
                        true,
                        app.getString(
                            R.string.diag_ocr_ok_fmt,
                            result.elements.size,
                            preview,
                        ),
                    )
                }
            } catch (throwable: Throwable) {
                items += DiagnosticItem(
                    app.getString(R.string.diag_ocr),
                    false,
                    app.getString(R.string.diag_ocr_failed_fmt, describe(throwable)),
                )
            } finally {
                runCatching { engine.close() }
                softwareCopy?.let { runCatching { it.recycle() } }
            }
        }

        // OCR 完成后释放本次截图（调用方拥有所有权）。
        screenshot?.let { runCatching { it.recycle() } }

        // 6. 输入后端（root / accessibility / dry_run）
        val inputConfig = config?.input ?: InputConfig()
        if (connectedService == null) {
            for (backend in INPUT_BACKENDS) {
                items += DiagnosticItem(
                    app.getString(R.string.diag_input_name_fmt, backend),
                    false,
                    app.getString(R.string.diag_detail_no_service_input),
                )
            }
        } else {
            val screenSize = runCatching { connectedService.screenSize() }
                .getOrDefault(1920 to 1080)
            for (backend in INPUT_BACKENDS) {
                val name = app.getString(R.string.diag_input_name_fmt, backend)
                runCatching {
                    InputFactory.create(
                        app,
                        inputConfig.copy(backend = backend),
                        connectedService,
                        screenSize,
                        LogBus,
                    )
                }.fold(
                    onSuccess = { input ->
                        val available = runCatching { input.isAvailable() }
                            .getOrDefault(false)
                        val backendName = runCatching { input.backendName() }
                            .getOrDefault(backend)
                        items += DiagnosticItem(
                            name,
                            available,
                            app.getString(
                                if (available) {
                                    R.string.diag_input_ok_fmt
                                } else {
                                    R.string.diag_input_unavailable_fmt
                                },
                                backendName,
                            ),
                        )
                        runCatching { input.close() }
                        Unit
                    },
                    onFailure = { throwable ->
                        items += DiagnosticItem(
                            name,
                            false,
                            app.getString(
                                R.string.diag_input_failed_fmt,
                                describe(throwable),
                            ),
                        )
                    },
                )
            }
        }

        // 7. 前台包名
        val foregroundItem = if (connectedService == null) {
            DiagnosticItem(
                app.getString(R.string.diag_foreground),
                false,
                app.getString(R.string.diag_foreground_unknown),
            )
        } else {
            val foreground = runCatching { connectedService.foregroundPackage() }.getOrNull()
            if (foreground.isNullOrBlank()) {
                DiagnosticItem(
                    app.getString(R.string.diag_foreground),
                    false,
                    app.getString(R.string.diag_foreground_unknown),
                )
            } else {
                val target = config?.gamePackage ?: "com.tencent.tmgp.dfm"
                val detail = if (foreground == target) {
                    app.getString(R.string.diag_foreground_match_fmt, foreground)
                } else {
                    app.getString(
                        R.string.diag_foreground_other_fmt,
                        foreground,
                        target,
                    )
                }
                // 能读到包名即视为该能力可用；是否为目标游戏在 detail 中说明。
                DiagnosticItem(app.getString(R.string.diag_foreground), true, detail)
            }
        }
        items += foregroundItem

        // 8. profile
        val profileResult = runCatching { ServiceLocator.profile() }
        val profile = profileResult.getOrNull()
        profileResult.fold(
            onSuccess = { loaded ->
                items += DiagnosticItem(
                    app.getString(R.string.diag_profile),
                    true,
                    app.getString(
                        R.string.diag_profile_ok_fmt,
                        loaded.name,
                        loaded.baseWidth,
                        loaded.baseHeight,
                    ),
                )
            },
            onFailure = { throwable ->
                items += DiagnosticItem(
                    app.getString(R.string.diag_profile),
                    false,
                    app.getString(R.string.diag_profile_failed_fmt, describe(throwable)),
                )
            },
        )

        // 9. catalog
        val catalogResult = runCatching { ServiceLocator.catalogIndex() }
        catalogResult.fold(
            onSuccess = { index ->
                val count = Dept.entries.sumOf { index.entries(it).size }
                items += DiagnosticItem(
                    app.getString(R.string.diag_catalog),
                    count > 0,
                    if (count > 0) {
                        app.getString(R.string.diag_catalog_ok_fmt, count)
                    } else {
                        app.getString(R.string.diag_catalog_failed_fmt, "catalog 为空")
                    },
                )
            },
            onFailure = { throwable ->
                items += DiagnosticItem(
                    app.getString(R.string.diag_catalog),
                    false,
                    app.getString(R.string.diag_catalog_failed_fmt, describe(throwable)),
                )
            },
        )

        // 10. plan
        val planResult = runCatching { ServiceLocator.loadPlan() }
        planResult.fold(
            onSuccess = { plan ->
                val count = plan.departments.values.sumOf { it.size }
                items += DiagnosticItem(
                    app.getString(R.string.diag_plan),
                    true,
                    app.getString(R.string.diag_plan_ok_fmt, count),
                )
            },
            onFailure = { throwable ->
                items += DiagnosticItem(
                    app.getString(R.string.diag_plan),
                    false,
                    app.getString(R.string.diag_plan_failed_fmt, describe(throwable)),
                )
            },
        )

        // 11. 屏幕分辨率（按横屏归一化后比较）
        var width = 0
        var height = 0
        runCatching { connectedService?.screenSize() }.getOrNull()?.let { (w, h) ->
            width = w
            height = h
        }
        if (width <= 0 || height <= 0) {
            val metrics = app.resources.displayMetrics
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        val landscapeWidth = max(width, height)
        val landscapeHeight = min(width, height)

        if (landscapeWidth <= 0 || landscapeHeight <= 0) {
            items += DiagnosticItem(
                app.getString(R.string.diag_resolution),
                false,
                app.getString(R.string.diag_resolution_unknown),
            )
        } else if (profile == null) {
            items += DiagnosticItem(
                app.getString(R.string.diag_resolution),
                false,
                app.getString(R.string.diag_resolution_no_profile),
            )
        } else {
            val ok = Geometry.isSupportedWidth(profile, landscapeWidth)
            items += DiagnosticItem(
                app.getString(R.string.diag_resolution),
                ok,
                if (ok) {
                    app.getString(
                        R.string.diag_resolution_ok_fmt,
                        landscapeWidth,
                        landscapeHeight,
                    )
                } else {
                    app.getString(
                        R.string.diag_resolution_bad_fmt,
                        landscapeWidth,
                        landscapeHeight,
                        profile.supportedWidths.joinToString(", "),
                    )
                },
            )
        }

        return items
    }

    private val INPUT_BACKENDS = listOf("root", "accessibility", "dry_run")

    private fun describe(throwable: Throwable): String =
        throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

    private fun copyToSoftware(bitmap: Bitmap): Bitmap? {
        if (bitmap.config != Bitmap.Config.HARDWARE) return null
        return runCatching {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
    }

    /**
     * 稀疏采样，判断截图是否全黑（FLAG_SECURE 的典型表现）。
     * 返回 (非黑采样点数, 总采样点数)；无法采样时返回 (1, 1) 以免误报。
     */
    private fun sampleNonBlack(bitmap: Bitmap): Pair<Int, Int> {
        val target = if (bitmap.config == Bitmap.Config.HARDWARE) {
            runCatching {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull() ?: return 1 to 1
        } else {
            bitmap
        }

        val stepX = (target.width / 32).coerceAtLeast(1)
        val stepY = (target.height / 18).coerceAtLeast(1)
        var total = 0
        var nonBlack = 0

        var y = 0
        while (y < target.height) {
            var x = 0
            while (x < target.width) {
                val color = target.getPixel(x, y)
                val luminance = Color.red(color) + Color.green(color) + Color.blue(color)
                if (luminance > 24) nonBlack++
                total++
                x += stepX
            }
            y += stepY
        }

        if (target !== bitmap) {
            runCatching { target.recycle() }
        }
        return nonBlack to total
    }
}
