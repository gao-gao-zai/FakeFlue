package com.flue.launcher.surface.mode

/**
 * R05 三种运行状态。隐藏管理是伪装模式中的临时管理访问，不是第四种模式。
 */
enum class SurfaceMode {
    /** 完整桌面：原版 Flue 完整功能，不施加伪装专属限制。 */
    FULL,

    /** 伪装桌面：普通手表外观、精简公开功能。 */
    DISGUISE,

    /** 外部桌面：用户选定的其他桌面；FFlue 不主动把用户拉回。 */
    EXTERNAL;

    /** 该模式读写哪一套模式配置（R06）。外部模式下若打开 FFlue，按完整桌面配置显示。 */
    val configScope: ConfigScope
        get() = when (this) {
            DISGUISE -> ConfigScope.DISGUISE
            FULL, EXTERNAL -> ConfigScope.FULL
        }

    companion object {
        fun fromStored(value: String?): SurfaceMode? = entries.firstOrNull { it.name == value }
    }
}

/** 模式配置的归属。设备级配置不属于任何一套，两种模式共用。 */
enum class ConfigScope { FULL, DISGUISE }
