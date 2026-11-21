package com.flue.launcher.surface.access

import com.flue.launcher.surface.mode.SurfaceMode

/**
 * 设置搜索条目对应的功能（R08）。本模式不公开的功能不进搜索结果，
 * 避免绕过页面上已经隐藏的入口。
 */
object SettingsSearchGate {
    private val storeItems = setOf("watchface_store", "watch_store_preview_cache", "watch_store_market_url")

    fun featureOf(destination: String, itemKey: String?): SurfaceFeature? = when {
        destination == "AGENT" -> SurfaceFeature.AGENT
        destination == "DONATE" -> SurfaceFeature.WATCH_STORE
        itemKey in storeItems -> SurfaceFeature.WATCH_STORE
        itemKey == "watchface_canvas_entry" -> SurfaceFeature.ADVANCED_CANVAS
        else -> null
    }

    /** 只拦“本模式不公开”的功能；需要管理访问的高级设置在 P4 接入管理验证前照常可搜。 */
    fun isVisible(mode: SurfaceMode, destination: String, itemKey: String?): Boolean {
        val feature = featureOf(destination, itemKey) ?: return true
        return FeatureAccessPolicy.decide(mode, feature) != AccessDecision.DENIED
    }
}
