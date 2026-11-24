package com.sjz.harvest.game

import com.sjz.harvest.capture.ScreenSource
import com.sjz.harvest.core.config.UiProfile
import com.sjz.harvest.core.logging.AppLogger
import com.sjz.harvest.core.model.Dept
import com.sjz.harvest.core.model.DeptState
import com.sjz.harvest.core.model.DeptStatus
import com.sjz.harvest.core.model.Observation
import com.sjz.harvest.core.model.OcrResult
import com.sjz.harvest.core.model.Page
import com.sjz.harvest.vision.DashboardStatusResolver
import com.sjz.harvest.vision.OcrEngine
import com.sjz.harvest.vision.PageDetector

/**
 * 只读感知实现：截全屏 → OCR → 判页 → 计算四部门状态。
 *
 * 不做任何点击；所有坐标来自 OCR 包围盒（实际屏幕像素）。
 * 页面不符或找不到部门标题时返回 UNKNOWN，让上层决定是否恢复/停止。
 *
 * 状态解析集中在 [DashboardStatusResolver]（纯 Kotlin），
 * 本类只负责截屏/OCR/判页和日志。
 */
class ObserverImpl(
    private val snapshotter: ScreenOcr,
    private val profile: UiProfile,
    private val logger: AppLogger,
    private val dumpOcrText: Boolean = false,
) : Observer {

    /** 生产用构造：内部包装截屏 + OCR（含前台校验与 Bitmap 回收）。 */
    constructor(
        source: ScreenSource,
        ocr: OcrEngine,
        profile: UiProfile,
        logger: AppLogger,
        foregroundGuard: ForegroundGuard = ForegroundGuard.ALWAYS_OK,
        dumpOcrText: Boolean = false,
    ) : this(
        snapshotter = ScreenOcrSource(
            source = source,
            ocr = ocr,
            foregroundGuard = foregroundGuard,
            doneBadge = profile.dashboard.doneBadge,
        ),
        profile = profile,
        logger = logger,
        dumpOcrText = dumpOcrText,
    )

    override suspend fun observe(): Observation {
        val snapshot = snapshotter.capture("observer")
        val screenWidth = snapshot.width
        val screenHeight = snapshot.height
        val ocrResult: OcrResult = snapshot.result

        if (ocrResult.elements.isEmpty()) {
            logger.w(TAG, "观察结果为空：OCR 未识别到文字")
            return Observation(
                page = Page.UNKNOWN,
                statuses = unknownStatuses("OCR 未识别到文字"),
                elements = emptyList(),
                detail = "OCR 未识别到文字",
            )
        }

        if (dumpOcrText) {
            // 分块输出，避免 logcat 单行超过 4k 被截断，丢掉后面的行。
            ocrResult.elements.chunked(OCR_DUMP_CHUNK).forEachIndexed { index, chunk ->
                logger.d(
                    TAG,
                    "OCR[$index/${(ocrResult.elements.size + OCR_DUMP_CHUNK - 1) / OCR_DUMP_CHUNK}] " +
                        chunk.joinToString(" | ") { element ->
                            "\"${element.text.replace('\n', ' ')}\"@" +
                                "${element.rect.x},${element.rect.y}," +
                                "${element.rect.w},${element.rect.h}"
                        },
                )
            }
        }

        val page = PageDetector.detect(ocrResult, profile)
        val labels = PageDetector.findDepartmentLabels(ocrResult, profile)

        val resolution = if (labels.isEmpty() && page != Page.DASHBOARD) {
            null
        } else {
            DashboardStatusResolver.resolve(
                ocr = ocrResult,
                profile = profile,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                alertBadges = snapshot.alertBadges,
            )
        }

        val statuses = resolution?.statuses
            ?: unknownStatuses("未找到部门标题")

        if (resolution != null && resolution.inferredDepts.isNotEmpty()) {
            logger.i(
                TAG,
                "部门标题缺失，锚点已推断: " +
                    resolution.inferredDepts.joinToString(",") { it.key },
            )
        }

        val detail = buildString {
            append("page=").append(page)
            append(", labels=").append(labels.size)
            resolution?.let { resolved ->
                append(", inferred=").append(
                    resolved.inferredDepts.joinToString(",") { it.key },
                )
            }
            if (snapshot.alertBadges.isNotEmpty()) {
                append(", badges=").append(snapshot.alertBadges.size)
            }
            append(", statuses=")
            append(
                Dept.entries.joinToString(",") { dept ->
                    val status = statuses[dept]
                    "${dept.key}:${status?.state}" +
                        (status?.remainingSec?.let { "(${it}s)" } ?: "")
                },
            )
        }
        logger.d(TAG, detail)

        return Observation(
            page = page,
            statuses = statuses,
            elements = ocrResult.elements,
            detail = detail,
        )
    }

    private fun unknownStatuses(reason: String): Map<Dept, DeptStatus> =
        Dept.entries.associateWith { dept ->
            DeptStatus(
                dept = dept,
                state = DeptState.UNKNOWN,
                confidence = 0f,
                detail = reason,
            )
        }

    companion object {
        private const val TAG = "Observer"

        /** 每条 OCR 日志最多包含多少个文字元素。 */
        private const val OCR_DUMP_CHUNK = 8
    }
}
