package com.flue.launcher.surface.display

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 用户校准的安全区（R23）：屏幕中央一块完整可见的圆角矩形。
 *
 * 宽、高是屏幕宽高的百分比（55–100），圆角半径是安全区短边的百分比（0–50），精度 0.1。
 * 50% 圆角加 100%×100% 在正方形屏幕上就是内切圆，即圆屏的默认值。
 */
data class SafeAreaSpec(
    val widthPercent: Float,
    val heightPercent: Float,
    val cornerPercent: Float,
) {
    /** 夹到合法范围并取 0.1 精度。存储和几何计算前都先过一遍。 */
    fun normalized(): SafeAreaSpec = SafeAreaSpec(
        widthPercent = tenth(widthPercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)),
        heightPercent = tenth(heightPercent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)),
        cornerPercent = tenth(cornerPercent.coerceIn(MIN_CORNER_PERCENT, MAX_CORNER_PERCENT)),
    )

    companion object {
        const val MIN_SIZE_PERCENT = 55f
        const val MAX_SIZE_PERCENT = 100f
        const val MIN_CORNER_PERCENT = 0f
        const val MAX_CORNER_PERCENT = 50f

        /** 未校准时：整屏可见；圆屏取内切圆，方屏不留圆角。 */
        fun default(roundScreen: Boolean): SafeAreaSpec =
            SafeAreaSpec(MAX_SIZE_PERCENT, MAX_SIZE_PERCENT, if (roundScreen) MAX_CORNER_PERCENT else 0f)

        fun tenth(value: Float): Float = (value * 10f).roundToInt() / 10f

        /** 解析精确输入。超出范围或不是数字返回 null，由调用方保留原值。 */
        fun parsePercent(text: String, range: ClosedFloatingPointRange<Float>): Float? =
            text.trim().replace(',', '.').removeSuffix("%").trim().toFloatOrNull()
                ?.takeIf { it.isFinite() && it in range }
                ?.let(::tenth)
    }
}

/**
 * 安全区在某个屏幕尺寸上的实际位置，单位与输入一致（px 或 dp 都行）。
 *
 * [left]/[top] 是普通内容的边界（R23：宽高决定普通内容边界）。
 * [barInset] 是常驻上下边缘的操作栏为避开圆角需要再内移、两侧再收窄的距离。
 */
data class SafeAreaFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float,
    val barInset: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

object SafeAreaGeometry {
    private val HALF_SQRT2 = (sqrt(2.0) / 2.0).toFloat()

    fun frame(screenWidth: Float, screenHeight: Float, spec: SafeAreaSpec): SafeAreaFrame {
        val s = spec.normalized()
        val width = screenWidth * s.widthPercent / 100f
        val height = screenHeight * s.heightPercent / 100f
        val radius = min(width, height) * s.cornerPercent / 100f
        return SafeAreaFrame(
            left = (screenWidth - width) / 2f,
            top = (screenHeight - height) / 2f,
            width = width,
            height = height,
            cornerRadius = radius,
            barInset = barInset(radius),
        )
    }

    /**
     * 固定栏的角点落在圆角弧的 45° 点上时刚好完整可见：
     * 角点到弧心距离 (r − d)·√2 = r，得 d = r·(1 − √2/2)。
     */
    fun barInset(cornerRadius: Float): Float = cornerRadius * (1f - HALF_SQRT2)
}
