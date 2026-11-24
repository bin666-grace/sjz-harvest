package com.sjz.harvest.core.model

import kotlinx.serialization.Serializable

/**
 * 跨层共享的数据类型。此文件不允许 import 任何 Android API，
 * 以便业务逻辑可以直接在 JVM 单元测试中运行。
 */

enum class Dept(val key: String, val label: String) {
    TECH("tech", "技术中心"),
    WORK("work", "工作台"),
    MEDICAL("medical", "制药台"),
    ARMOR("armor", "防具台");

    companion object {
        fun fromKey(key: String): Dept? = entries.firstOrNull { it.key == key }
    }
}

enum class DeptState {
    /** 空闲中，可以开始新一轮制造 */
    IDLE,

    /** 制造中，有剩余时间 */
    CRAFTING,

    /** 制造完成，等待收取 */
    DONE,

    /** 无法判断，禁止盲操作 */
    UNKNOWN,
}

enum class Page {
    DASHBOARD,
    DEPARTMENT,
    CRAFT_DIALOG,
    AUCTION,
    UNKNOWN,
}

enum class ActionKind {
    FOCUS,
    CLICK,
    TAP,
    SWIPE,
    BACK,
    WAIT,
    COLLECT,
    CRAFT,
    BUY,
    SCROLL,
    SKIP,
    STOP,
}

@Serializable
data class PointI(val x: Int, val y: Int) {
    fun offset(dx: Int, dy: Int): PointI = PointI(x + dx, y + dy)

    fun scaled(factor: Double): PointI =
        PointI((x * factor).toInt(), (y * factor).toInt())
}

@Serializable
data class RectI(val x: Int, val y: Int, val w: Int, val h: Int) {
    val center: PointI
        get() = PointI(x + w / 2, y + h / 2)

    val right: Int get() = x + w
    val bottom: Int get() = y + h

    fun contains(point: PointI): Boolean =
        point.x in x until right && point.y in y until bottom

    fun inset(px: Int): RectI =
        RectI(x + px, y + px, (w - 2 * px).coerceAtLeast(1), (h - 2 * px).coerceAtLeast(1))

    fun scaled(factor: Double): RectI = RectI(
        (x * factor).toInt(),
        (y * factor).toInt(),
        (w * factor).toInt(),
        (h * factor).toInt(),
    )

    fun isEmpty(): Boolean = w <= 0 || h <= 0

    companion object {
        fun fromLTRB(left: Int, top: Int, right: Int, bottom: Int): RectI =
            RectI(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
    }
}

/** OCR 识别出的一段文字及其在画面中的包围盒。 */
data class TextElement(val text: String, val rect: RectI)

data class OcrResult(
    val elements: List<TextElement>,
    val fullText: String,
) {
    companion object {
        val EMPTY = OcrResult(emptyList(), "")
    }
}

data class DeptStatus(
    val dept: Dept,
    val state: DeptState,
    val remainingSec: Int? = null,
    val confidence: Float = 1f,
    val detail: String = "",
) {
    val isActionable: Boolean
        get() = state == DeptState.IDLE || state == DeptState.DONE
}

data class Observation(
    val page: Page,
    val statuses: Map<Dept, DeptStatus> = emptyMap(),
    val elements: List<TextElement> = emptyList(),
    val detail: String = "",
) {
    fun status(dept: Dept): DeptStatus =
        statuses[dept] ?: DeptStatus(dept, DeptState.UNKNOWN, detail = "missing")
}

data class ActionRecord(
    val kind: ActionKind,
    val detail: String,
    val atMs: Long = System.currentTimeMillis(),
)

data class CycleResult(
    val statuses: Map<Dept, DeptStatus>,
    val actions: List<ActionRecord>,
    val nextWakeSec: Long,
    val stopped: Boolean = false,
    val error: String? = null,
)

/** 页面/部门状态的组合结果，供通知栏和 UI 展示。 */
data class Projection(
    val page: Page,
    val statuses: Map<Dept, DeptStatus>,
)
