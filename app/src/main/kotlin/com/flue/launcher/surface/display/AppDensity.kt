package com.flue.launcher.surface.display

import kotlin.math.roundToInt

/**
 * 应用独立 DPI（R24）的纯规则：范围、默认值和换算。Android 部分在 [DisplayAdaptation]。
 */
object AppDensity {
    const val MIN_DPI = 80
    const val MAX_DPI = 640

    fun clamp(dpi: Int): Int = dpi.coerceIn(MIN_DPI, MAX_DPI)

    /** 已保存值优先；未配置时用设备稳定默认密度，不跟随用户给其他应用调的系统 DPI。 */
    fun effective(savedDpi: Int?, stableDpi: Int): Int = savedDpi?.let(::clamp) ?: stableDpi

    /** 保存时：等于稳定默认值就存“未配置”，以后换系统镜像也跟着设备默认走。 */
    fun toStored(dpi: Int, stableDpi: Int): Int? = clamp(dpi).takeIf { it != stableDpi }

    /** 整数精确输入。不是整数或超出范围返回 null。 */
    fun parseInput(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN_DPI..MAX_DPI }

    /** 屏幕的 dp 尺寸随密度换算，保持像素尺寸不变。 */
    fun rescaleDp(dp: Int, fromDpi: Int, toDpi: Int): Int =
        if (fromDpi <= 0 || toDpi <= 0) dp else (dp.toFloat() * fromDpi / toDpi).roundToInt()
}
