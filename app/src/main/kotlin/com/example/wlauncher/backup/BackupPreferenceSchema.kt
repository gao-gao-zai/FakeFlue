package com.flue.launcher.backup

import com.flue.launcher.surface.config.LauncherSettingsScopes

/**
 * 备份「设置」区段允许写回 `launcher_settings` 的键与类型（R37）。
 *
 * 备份里的每一项都自带 `type`，以前导入照单全收：任何键名、任何类型都直接写进
 * DataStore。类型对不上的值（例如把 `blur_enabled` 写成字符串）会让设置收集端在
 * `prefs[booleanKey]` 处抛 ClassCastException，桌面启动即崩、反复崩。现在只接受
 * 本应用认识的键，且类型必须与这里登记的一致；其余跳过并计数。
 *
 * 名单与 `LauncherViewModel` 里的 `KEY_*` 一一对应，单测会用反射核对类型与覆盖面，
 * 新增设置键时漏登记会直接测试失败。伪装模式的物理键（`fflue.disguise::` 前缀）
 * 按去掉前缀后的原名查类型。
 */
internal object BackupPreferenceSchema {
    enum class Type(val wireName: String) { BOOLEAN("boolean"), INT("int"), STRING("string") }

    private val booleanNames = setOf(
        "side_screen_enabled", "blur_enabled", "edge_blur_enabled", "low_res_icons", "legacy_circular_icons",
        "animation_override_enabled", "splash_icon", "direct_launch_app_list", "app_list_fisheye_enabled",
        "material_honeycomb_top_fisheye_enabled", "app_list_edge_spacing_compression_enabled",
        "app_list_watchface_colors", "app_list_row_border_enabled", "app_list_grid_icon_plate_enabled",
        "app_list_folders_enabled", "app_list_scroll_indicator_enabled", "fast_flow_animation_enabled",
        "two_tone_icons_enabled", "app_icon_shadow_enabled", "app_list_return_shrink_enabled",
        "face_return_shrink_enabled", "legacy_app_list_transition_enabled",
        "app_list_return_icon_float_enabled", "reduce_dynamic_effects", "app_list_stagger_enabled",
        "flow_spacing_disabled_v1", "show_step_count", "show_notification", "show_ongoing_notifications",
        "rotary_haptics_enabled", "notification_setting_migrated", "show_widget_page", "show_control_center",
        "show_music_controls", "show_media_custom_actions", "swap_music_notification_components",
        "double_tap_lock_screen_enabled", "face_ignores_back_key", "power_menu_button_enabled",
        "watchface_charging_power_text", "watchface_text_entry_animation", "watchface_text_exit_animation",
        "watchface_status_indicators", "watchface_dnd_indicator", "watchface_bottom_fade_enabled",
        "watchmaker_force_fill", "watchface_gravity_parallax_enabled",
        "watchface_gravity_backdrop_blur_enabled", "device_preset_applied", "screen_off_keep_app",
        "debug_force_missing_watchface", "photo_clock_bold", "video_clock_bold", "video_fill_screen",
        "photo_use_theme_text_color", "video_use_theme_text_color", "photo_md3e_auto_colors",
        "video_md3e_auto_colors", "photo_show_seconds", "video_show_seconds", "builtin_manager_thumbnails",
        "hide_from_recents", "watch_store_segmented_download", "glass_highlight_enabled",
        "widget_glass_highlight_enabled",
    )

    private val intNames = setOf(
        "splash_delay", "honeycomb_cols", "honeycomb_top_blur", "honeycomb_bottom_blur",
        "honeycomb_edge_blur_tenths", "honeycomb_top_fade", "honeycomb_bottom_fade",
        "app_list_fisheye_range_rows", "app_list_fisheye_strength_percent",
        "app_list_left_safe_inset_percent", "app_list_scale_percent", "global_ui_scale_percent",
        "overlay_mask_corner_radius_dp", "photo_clock_size", "video_clock_size", "photo_text_color",
        "video_text_color", "photo_md3e_text_color", "video_md3e_text_color", "photo_md3e_face_color",
        "video_md3e_face_color", "photo_md3e_hour_color", "video_md3e_hour_color", "photo_md3e_minute_color",
        "video_md3e_minute_color", "photo_md3e_second_color", "video_md3e_second_color",
        "side_screen_shortcut_rows", "side_screen_shortcut_cols",
    )

    private val stringNames = setOf(
        "layout_mode", "theme_mode", "transition_motion", "ui_style", "side_screen_shortcuts", "app_order",
        "music_text_switch_animation", "screen_shape_override", "motion_profile_id",
        "custom_motion_profiles", "layout_profiles", "active_layout_profile_ids", "app_list_background",
        "watchface_long_press_mode", "watchface_canvas_default", "watchface_canvas_overrides",
        "watchface_date_locale", "hidden_apps", "public_apps", "notification_allowed_apps",
        "public_app_overrides", "hidden_drawer_pinned", "icon_pack_package", "selected_watchface_id",
        "last_watchface_error", "builtin_photo_path", "builtin_video_path", "photo_clock_position",
        "video_clock_position", "video_clock_color_mode", "watchface_font_path", "photo_clock_style",
        "video_clock_style", "photo_md3e_shape", "video_md3e_shape", "photo_custom_text",
        "video_custom_text", "app_language", "side_screen_widgets",
    )

    val knownLogicalNames: Set<String> = booleanNames + intNames + stringNames

    fun typeOfLogical(name: String): Type? = when (name) {
        in booleanNames -> Type.BOOLEAN
        in intNames -> Type.INT
        in stringNames -> Type.STRING
        else -> null
    }

    /** 物理键名（可能带伪装前缀）应有的类型；不认识的键返回 null。 */
    fun typeOfPhysical(physical: String): Type? {
        if (!LauncherSettingsScopes.isDisguisePhysical(physical)) return typeOfLogical(physical)
        val logical = LauncherSettingsScopes.logicalNameOfDisguise(physical)
        // 设备级键没有伪装副本；带前缀出现说明来源不可信，按未知处理。
        if (LauncherSettingsScopes.isDeviceGlobal(logical)) return null
        return typeOfLogical(logical)
    }
}
