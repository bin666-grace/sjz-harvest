package com.sjz.harvest.vision

import com.sjz.harvest.core.config.AuctionProfile
import com.sjz.harvest.core.config.TextProfile
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 纯 Kotlin 文本解析工具（不依赖 Android API，可在 JVM 单测中直接运行）。
 *
 * 职责：
 * - 计时器 / 价格解析；
 * - 空闲 / 完成关键词判断；
 * - OCR 文本归一化（去空白标点、全半角、常见错字替换）；
 * - 归一化 Levenshtein 相似度与候选项最佳匹配。
 */
object TextParser {

    /** 部门进度文字的最佳匹配结果。 */
    data class Match(val name: String, val score: Int)

    private val DEFAULT_IDLE_KEYWORDS = listOf("空闲中", "空闲")
    private val DEFAULT_DONE_KEYWORDS = listOf("收取", "领取", "已完成", "完成")

    private const val FALLBACK_TIMER_REGEX = "(\\d{1,3}):(\\d{2}):(\\d{2})"
    private const val FALLBACK_SHORT_TIMER_REGEX = "(\\d{1,3}):(\\d{2})"
    private const val FALLBACK_PRICE_REGEX = "\\d[\\d,]{2,}"

    // ---------------------------------------------------------------------
    // 计时器 / 价格
    // ---------------------------------------------------------------------

    /**
     * 解析计时器文本，返回剩余秒数。
     *
     * 支持 `HH:MM:SS` 与 `MM:SS`，容忍全角数字/冒号（OCR 常见）。
     * 优先使用 profile.timerRegex，其次 shortTimerRegex；都失败时使用内置正则。
     */
    fun parseTimer(text: String, profile: TextProfile): Int? {
        if (text.isBlank()) return null
        val canonical = canonicalizeDigits(text)

        parseLongTimer(canonical, profile.timerRegex)?.let { return it }
        parseLongTimer(canonical, FALLBACK_TIMER_REGEX)?.let { return it }
        parseShortTimer(canonical, profile.shortTimerRegex)?.let { return it }
        parseShortTimer(canonical, FALLBACK_SHORT_TIMER_REGEX)?.let { return it }
        return null
    }

    private fun parseLongTimer(text: String, regex: String): Int? {
        val match = safeRegex(regex)?.find(text) ?: return null
        if (match.groupValues.size < 4) return null
        val h = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: return null
        val s = match.groupValues[3].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    private fun parseShortTimer(text: String, regex: String): Int? {
        val match = safeRegex(regex)?.find(text) ?: return null
        if (match.groupValues.size < 3) return null
        val m = match.groupValues[1].toIntOrNull() ?: return null
        val s = match.groupValues[2].toIntOrNull() ?: return null
        return m * 60 + s
    }

    /**
     * 解析价格：取匹配到的最长「数字+逗号」串，去掉逗号后转 Int。
     * 超过 Int 范围或没有匹配时返回 null。
     */
    fun parsePrice(text: String, profile: AuctionProfile): Int? {
        if (text.isBlank()) return null
        val regex = safeRegex(profile.priceRegex) ?: safeRegex(FALLBACK_PRICE_REGEX) ?: return null
        return regex.findAll(text)
            .map { it.value }
            .filter { raw -> raw.any { it.isDigit() } }
            .maxByOrNull { raw -> raw.count { it.isDigit() } }
            ?.let { raw -> raw.filter { it.isDigit() }.toIntOrNull() }
    }

    // ---------------------------------------------------------------------
    // 状态关键词
    // ---------------------------------------------------------------------

    fun isIdle(text: String, keywords: List<String> = DEFAULT_IDLE_KEYWORDS): Boolean =
        containsAny(text, keywords)

    fun isDone(text: String, keywords: List<String> = DEFAULT_DONE_KEYWORDS): Boolean =
        containsAny(text, keywords)

    /** 归一化后判断 text 是否包含任一关键词。 */
    fun containsAny(text: String, keywords: List<String>): Boolean {
        if (text.isBlank()) return false
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        return keywords.any { keyword ->
            val k = normalize(keyword)
            k.isNotEmpty() && normalized.contains(k)
        }
    }

    // ---------------------------------------------------------------------
    // 归一化 / 相似度
    // ---------------------------------------------------------------------

    /**
     * 归一化文本：NFKC → 去空白与标点 → 应用 OCR 错字替换。
     *
     * 注意：不改变大小写，避免 `BP`/`Bp` 这类弹药型号被错误合并；
     * 模板与识别文本会做同样的归一化，所以空白/标点差异不会影响匹配。
     */
    fun normalize(text: String, profile: TextProfile = TextProfile()): String {
        if (text.isEmpty()) return ""
        val nfkc = try {
            Normalizer.normalize(text, Normalizer.Form.NFKC)
        } catch (_: Exception) {
            text
        }
        val builder = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            // isLetterOrDigit 对中文也为 true；标点/空白全部丢弃。
            if (ch.isLetterOrDigit()) builder.append(ch)
        }
        var result = builder.toString()
        for ((from, to) in profile.ocrReplacements) {
            if (from.isNotEmpty()) result = result.replace(from, to)
        }
        return result
    }

    /** 归一化 Levenshtein 相似度，范围 0..100。 */
    fun similarity(a: String, b: String): Int {
        if (a == b) return 100
        if (a.isEmpty() || b.isEmpty()) return 0
        val distance = levenshtein(a, b)
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 100
        return ((1.0 - distance.toDouble() / maxLen) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /**
     * 在候选项中找到与 text 最相似的一项。
     * 归一化后比较；分数低于 threshold 时返回 null。
     */
    fun bestMatch(text: String, candidates: List<String>, threshold: Int): Match? {
        if (candidates.isEmpty()) return null
        val target = normalize(text)
        if (target.isEmpty()) return null
        var best: Match? = null
        for (candidate in candidates) {
            val normalized = normalize(candidate)
            if (normalized.isEmpty()) continue
            val score = when {
                normalized == target -> 100
                // 只允许「较长的候选包含完整短目标」。若候选本身只有 1-3 个字，
                // 它是长目标的子串并不能证明匹配（例如「弹」vs「9x39mm 弹」）。
                (target.length >= 3 && normalized.length >= target.length &&
                    normalized.contains(target)) ||
                    (normalized.length >= 3 && target.length >= normalized.length &&
                        target.contains(normalized)) -> 95
                else -> similarity(target, normalized)
            }
            if (best == null || score > best.score) {
                best = Match(candidate, score)
            }
        }
        return best?.takeIf { it.score >= threshold }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private fun canonicalizeDigits(text: String): String {
        var needsRewrite = false
        for (ch in text) {
            if (ch in '０'..'９' || ch == '：') {
                needsRewrite = true
                break
            }
        }
        if (!needsRewrite) return text
        return buildString(text.length) {
            for (ch in text) {
                when (ch) {
                    in '０'..'９' -> append('0' + (ch - '０'))
                    '：' -> append(':')
                    else -> append(ch)
                }
            }
        }
    }

    private fun safeRegex(pattern: String): Regex? =
        try {
            if (pattern.isBlank()) null else Regex(pattern)
        } catch (_: Exception) {
            null
        }
}
