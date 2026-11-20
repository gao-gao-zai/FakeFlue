package com.flue.launcher.surface.config

import com.flue.launcher.surface.mode.ConfigScope

/**
 * R06：`launcher_settings` 中每个键的归属。
 *
 * - [deviceGlobalKeys]：设备级，完整／伪装共用一份（安全区、DPI 一类的设备事实与全局偏好）。
 * - [modeScopedKeys]：随模式区分（表盘、布局、侧屏、外观、通知等）。
 *
 * 物理存储仍是同一个 DataStore，保持“唯一来源”：完整模式直接使用原版键名（原版数据即完整配置，
 * 不需要迁移），伪装模式的随模式键加 [DISGUISE_PREFIX] 前缀存放。
 *
 * 新增设置键必须在这里二选一登记；`LauncherSettingsScopesTest` 会检查 LauncherViewModel
 * 中声明的每个键都已分类。运行时遇到未登记的键（例如只用于清理的旧键）按随模式处理，
 * 避免一个模式的改动泄漏到另一个模式。
 */
object LauncherSettingsScopes {

    const val DISGUISE_PREFIX = "fflue.disguise::"

    val deviceGlobalKeys: Set<String> = setOf(
        // R24：原 UI 大小比例属于设备级显示适配。
        "global_ui_scale_percent",
        // 屏幕形状是设备事实。
        "screen_shape_override",
        "app_language",
        "hide_from_recents",
        // 表盘市场下载调优，只在完整模式可用，不需要两份。
        "watch_store_segmented_download",
        "debug_force_missing_watchface",
    )

    val modeScopedKeys: Set<String> = setOf(
        // 外观、主题与动效
        "layout_mode", "theme_mode", "transition_motion", "ui_style",
        "blur_enabled", "edge_blur_enabled", "low_res_icons", "legacy_circular_icons",
        "animation_override_enabled", "splash_icon", "splash_delay", "direct_launch_app_list",
        "fast_flow_animation_enabled", "music_text_switch_animation", "two_tone_icons_enabled",
        "app_icon_shadow_enabled", "reduce_dynamic_effects", "motion_profile_id",
        "custom_motion_profiles", "glass_highlight_enabled", "widget_glass_highlight_enabled",
        "icon_pack_package", "overlay_mask_corner_radius_dp",
        // 应用列表与布局（R10 公开菜单与布局）
        "app_order", "hidden_apps", "public_apps", "honeycomb_cols", "honeycomb_top_blur", "honeycomb_bottom_blur",
        "honeycomb_edge_blur_tenths", "honeycomb_top_fade", "honeycomb_bottom_fade",
        "app_list_fisheye_enabled", "material_honeycomb_top_fisheye_enabled",
        "app_list_fisheye_range_rows", "app_list_fisheye_strength_percent",
        "app_list_edge_spacing_compression_enabled", "app_list_left_safe_inset_percent",
        "app_list_scale_percent", "app_list_watchface_colors", "app_list_row_border_enabled",
        "app_list_grid_icon_plate_enabled", "app_list_folders_enabled",
        "app_list_scroll_indicator_enabled", "app_list_return_shrink_enabled",
        "face_return_shrink_enabled", "legacy_app_list_transition_enabled",
        "app_list_return_icon_float_enabled", "app_list_stagger_enabled", "app_list_background",
        "layout_profiles", "active_layout_profile_ids",
        // 迁移／预设标记跟随它们写入的随模式值：每个模式各自完成一次。
        "flow_spacing_disabled_v1", "device_preset_applied", "notification_setting_migrated",
        // 侧屏与小组件
        "side_screen_enabled", "side_screen_shortcuts", "side_screen_widgets",
        "side_screen_shortcut_rows", "side_screen_shortcut_cols", "show_widget_page",
        // 通知、控制中心与手势
        "show_step_count", "show_notification", "show_ongoing_notifications",
        "rotary_haptics_enabled", "show_control_center", "show_music_controls",
        "show_media_custom_actions", "swap_music_notification_components",
        "double_tap_lock_screen_enabled", "face_ignores_back_key", "power_menu_button_enabled",
        // 表盘选择与表盘显示
        "selected_watchface_id", "last_watchface_error",
        "watchface_charging_power_text", "watchface_text_entry_animation",
        "watchface_text_exit_animation", "watchface_status_indicators", "watchface_dnd_indicator",
        "watchface_bottom_fade_enabled", "watchface_long_press_mode", "watchmaker_force_fill",
        "watchface_canvas_default", "watchface_canvas_overrides",
        "watchface_gravity_parallax_enabled", "watchface_gravity_backdrop_blur_enabled",
        "watchface_date_locale", "watchface_font_path", "builtin_manager_thumbnails",
        // 内置图片／视频表盘（媒体文件也按模式分目录，见 InternalWatchFaceStorage）
        "builtin_photo_path", "builtin_video_path",
        "photo_clock_position", "video_clock_position", "photo_clock_size", "video_clock_size",
        "photo_clock_bold", "video_clock_bold", "video_fill_screen", "video_clock_color_mode",
        "photo_clock_style", "video_clock_style", "photo_md3e_shape", "video_md3e_shape",
        "photo_use_theme_text_color", "video_use_theme_text_color",
        "photo_text_color", "video_text_color", "photo_md3e_auto_colors", "video_md3e_auto_colors",
        "photo_md3e_text_color", "video_md3e_text_color", "photo_md3e_face_color",
        "video_md3e_face_color", "photo_md3e_hour_color", "video_md3e_hour_color",
        "photo_md3e_minute_color", "video_md3e_minute_color", "photo_md3e_second_color",
        "video_md3e_second_color", "photo_show_seconds", "video_show_seconds",
        "photo_custom_text", "video_custom_text",
    )

    fun isDeviceGlobal(name: String): Boolean = name in deviceGlobalKeys

    /** 逻辑键在某一模式下的物理键名。 */
    fun physicalName(name: String, scope: ConfigScope): String =
        if (scope == ConfigScope.DISGUISE && !isDeviceGlobal(name)) DISGUISE_PREFIX + name else name

    /** 物理键是否属于伪装配置。 */
    fun isDisguisePhysical(physical: String): Boolean = physical.startsWith(DISGUISE_PREFIX)

    fun logicalNameOfDisguise(physical: String): String = physical.removePrefix(DISGUISE_PREFIX)
}
