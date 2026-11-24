package com.sjz.harvest.input

import android.content.Context
import com.sjz.harvest.core.config.InputConfig
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.SjzException
import com.sjz.harvest.service.SjzAccessibilityService

/**
 * 根据配置创建输入后端。
 *
 * backend:
 * - auto          root 可用 -> root；否则无障碍可用 -> accessibility；否则报错
 * - root          强制 root
 * - accessibility 强制无障碍手势
 * - dry_run       只记录不执行（优先级最高，安全检查也会单独覆盖）
 */
object InputFactory {

    private const val TAG = "InputFactory"

    suspend fun create(
        @Suppress("UNUSED_PARAMETER") context: Context,
        config: InputConfig,
        service: SjzAccessibilityService?,
        screenSize: Pair<Int, Int>,
        logger: AppLogger,
        /** 自动化传入游戏包名；每次手势前校验前台，诊断可传 null。 */
        requiredPackage: String? = null,
    ): InputController {
        val backend = config.backend.trim().lowercase()

        if (backend == "dry_run" || backend == "dryrun" || backend == "dry-run") {
            logger.i(TAG, "输入后端：dry-run（不执行任何点击）")
            return DryRunInput(logger)
        }

        return when (backend) {
            "root" -> {
                val root = RootShellInput.create(config, screenSize, logger)
                if (!root.isAvailable()) {
                    root.close()
                    throw SjzException.InputUnavailable("root 输入不可用：su 未授权或设备未 root")
                }
                root
            }

            "accessibility" -> accessibility(
                config,
                service,
                screenSize,
                logger,
                requiredPackage,
            )

            "auto", "" -> {
                val root = RootShellInput.create(config, screenSize, logger)
                if (root.isAvailable()) {
                    logger.i(TAG, "auto 选择 root 输入后端")
                    root
                } else {
                    root.close()
                    if (service != null) {
                        logger.i(TAG, "auto 选择无障碍手势输入后端")
                        AccessibilityGestureInput(
                            serviceProvider = { SjzAccessibilityService.instance.value ?: service },
                            config = config,
                            screenSize = screenSize,
                            logger = logger,
                            requiredPackage = requiredPackage,
                        )
                    } else {
                        throw SjzException.InputUnavailable(
                            "root 不可用且无障碍服务未连接，无法执行点击",
                        )
                    }
                }
            }

            else -> throw SjzException.InputUnavailable("未知的输入后端: ${config.backend}")
        }
    }

    private fun accessibility(
        config: InputConfig,
        service: SjzAccessibilityService?,
        screenSize: Pair<Int, Int>,
        logger: AppLogger,
        requiredPackage: String?,
    ): InputController {
        if (service == null) {
            throw SjzException.AccessibilityServiceUnavailable()
        }
        return AccessibilityGestureInput(
            serviceProvider = { SjzAccessibilityService.instance.value ?: service },
            config = config,
            screenSize = screenSize,
            logger = logger,
            requiredPackage = requiredPackage,
        )
    }
}
