package com.flue.launcher.surface.access

import com.flue.launcher.surface.mode.SurfaceMode

/** R08 表格中按功能划分的能力。 */
enum class SurfaceFeature {
    /** 表盘显示、选择、切换、基础数据。 */
    WATCH_FACE_BASIC,

    /** 表盘可视化编辑、新建、预览、保存。 */
    WATCH_FACE_EDITOR,

    /** 脚本编辑。 */
    SCRIPT_EDITOR,

    /** 表盘导出。 */
    WATCH_FACE_EXPORT,

    /** 通用文件管理。 */
    FILE_MANAGER,

    /** 高级画布。 */
    ADVANCED_CANVAS,

    /** 表盘市场、账号、上传、评论、社区、捐赠和开发链接。 */
    WATCH_STORE,

    /** AI、模型配置、工作区、Linux、终端。 */
    AGENT,

    /** 动画、图标包、调试、日志、备份等高级配置。 */
    ADVANCED_SETTINGS,

    /** 完整设置页。 */
    FULL_SETTINGS,

    /** 新增工具、电话、短信。 */
    TOOLS,
}

/** 访问决定。 */
enum class AccessDecision {
    ALLOWED,

    /** 只能通过已验证的管理访问使用（R08、R21）。 */
    REQUIRES_MANAGEMENT,

    /** 本模式不公开，执行时也限制。 */
    DENIED,
}

/** R08：模式 × 功能 × 是否在管理流程中 → 是否允许。 */
object FeatureAccessPolicy {

    fun decide(mode: SurfaceMode, feature: SurfaceFeature, inManagement: Boolean = false): AccessDecision {
        // 外部模式下打开 FFlue 按完整桌面显示（SurfaceMode.configScope）。
        if (mode != SurfaceMode.DISGUISE) return AccessDecision.ALLOWED
        return when (feature) {
            SurfaceFeature.WATCH_FACE_BASIC,
            SurfaceFeature.WATCH_FACE_EDITOR,
            SurfaceFeature.TOOLS,
            -> AccessDecision.ALLOWED

            SurfaceFeature.ADVANCED_SETTINGS,
            SurfaceFeature.FULL_SETTINGS,
            -> if (inManagement) AccessDecision.ALLOWED else AccessDecision.REQUIRES_MANAGEMENT

            // “不公开，执行时也限制”：管理访问也不能打开。
            SurfaceFeature.SCRIPT_EDITOR,
            SurfaceFeature.WATCH_FACE_EXPORT,
            SurfaceFeature.FILE_MANAGER,
            SurfaceFeature.ADVANCED_CANVAS,
            SurfaceFeature.WATCH_STORE,
            SurfaceFeature.AGENT,
            -> AccessDecision.DENIED
        }
    }

    fun isAllowed(mode: SurfaceMode, feature: SurfaceFeature, inManagement: Boolean = false): Boolean =
        decide(mode, feature, inManagement) == AccessDecision.ALLOWED
}
