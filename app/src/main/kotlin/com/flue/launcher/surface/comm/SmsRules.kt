package com.flue.launcher.surface.comm

/** 一条短信（可能分成多段）的发送结果。 */
enum class SmsSendState { SENDING, SENT, FAILED }

/** 发送失败原因（`SmsManager.RESULT_ERROR_*`）。 */
enum class SmsFailure { GENERIC, RADIO_OFF, NULL_PDU, NO_SERVICE, LIMIT_EXCEEDED, OTHER }

object SmsRules {
    /** `Activity.RESULT_OK`。 */
    const val RESULT_OK = -1

    fun failureOf(resultCode: Int): SmsFailure? = when (resultCode) {
        RESULT_OK -> null
        1 -> SmsFailure.GENERIC
        2 -> SmsFailure.RADIO_OFF
        3 -> SmsFailure.NULL_PDU
        4 -> SmsFailure.NO_SERVICE
        5 -> SmsFailure.LIMIT_EXCEEDED
        else -> SmsFailure.OTHER
    }

    private const val GSM_BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXTENDED = "^{}\\[~]|€\u000C"

    /**
     * 估算分段数（显示用，实际分段以系统 `SmsManager.divideMessage` 为准）：
     * 全部是 GSM 7 位字符时单条 160、多段每段 153；否则按 UCS-2 单条 70、多段每段 67。
     */
    fun estimateSegments(text: String): Int {
        if (text.isEmpty()) return 0
        val gsm = text.all { it in GSM_BASIC || it in GSM_EXTENDED }
        return if (gsm) {
            var septets = 0
            text.forEach { septets += if (it in GSM_EXTENDED) 2 else 1 }
            if (septets <= 160) 1 else (septets + 152) / 153
        } else {
            val units = text.length
            if (units <= 70) 1 else (units + 66) / 67
        }
    }

    /**
     * `sms:`、`smsto:`、`mms:`、`mmsto:` 里的收件人。多个号码用 `,` 或 `;` 分隔；已解码的 URI 部分传入。
     */
    fun recipients(schemeSpecificPart: String?): List<String> =
        schemeSpecificPart.orEmpty()
            .substringBefore('?')
            .split(',', ';')
            .map { DialRules.normalize(it.trim()) }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 多段来信合并：同一发件人的各段按到达顺序拼接。 */
    fun combine(parts: List<Pair<String?, String?>>): List<Pair<String, String>> {
        val byAddress = LinkedHashMap<String, StringBuilder>()
        parts.forEach { (address, body) ->
            val key = address?.takeIf { it.isNotBlank() } ?: return@forEach
            byAddress.getOrPut(key) { StringBuilder() }.append(body.orEmpty())
        }
        return byAddress.map { (k, v) -> k to v.toString() }
    }
}

/**
 * 多段短信的发送回执合并（R32 发送结果如实显示）：每段都成功才算已发送，任一段失败即失败；
 * 回执不齐就是发送中。同一段的重复回执以第一次为准。
 */
class SmsSendTracker(val parts: Int) {
    private val results = arrayOfNulls<Int>(parts.coerceAtLeast(1))

    /** @return 合并后的状态。 */
    fun report(part: Int, resultCode: Int): SmsSendState {
        if (part in results.indices && results[part] == null) results[part] = resultCode
        return state
    }

    val state: SmsSendState
        get() = when {
            results.any { it != null && it != SmsRules.RESULT_OK } -> SmsSendState.FAILED
            results.all { it == SmsRules.RESULT_OK } -> SmsSendState.SENT
            else -> SmsSendState.SENDING
        }

    /** 第一个失败段的原因。 */
    val failure: SmsFailure?
        get() = results.firstOrNull { it != null && it != SmsRules.RESULT_OK }?.let(SmsRules::failureOf)

    /** 序列化为 `parts|r0,r1,...`（空位写 _），跨进程（回执可能在新进程里到达）保存。 */
    fun serialize(): String = "$parts|" + results.joinToString(",") { it?.toString() ?: "_" }

    companion object {
        fun parse(raw: String?): SmsSendTracker? {
            val text = raw ?: return null
            val count = text.substringBefore('|').toIntOrNull()?.takeIf { it > 0 } ?: return null
            val tracker = SmsSendTracker(count)
            text.substringAfter('|', "").split(',').forEachIndexed { i, v -> v.toIntOrNull()?.let { tracker.report(i, it) } }
            return tracker
        }
    }
}
