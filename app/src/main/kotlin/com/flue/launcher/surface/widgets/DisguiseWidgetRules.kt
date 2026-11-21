package com.flue.launcher.surface.widgets

import com.flue.launcher.surface.mode.ConfigScope

/**
 * 伪装模式小组件页的进入与展示规则（R11）。
 *
 * 槽位本身随模式分开保存（`side_screen_widgets` 是随模式键）。伪装模式额外要求：
 * - 只展示提供方是公开应用的小组件；取消公开后隐藏而不删除，重新公开即恢复。
 * - 未启用、未配置、全部失效或删除了最后一个时，不允许进入空页；当前在该页时回表盘。
 * - 普通伪装页不能添加或编辑；本次管理流程有效时按完整模式处理，可进空页添加。
 */
object DisguiseWidgetRules {

    /** 当前能否编辑小组件页（显示添加、编辑、长按进入编辑）。 */
    fun editable(scope: ConfigScope, managementSession: Boolean): Boolean =
        scope == ConfigScope.FULL || managementSession

    /** 能否进入小组件页。 */
    fun pageAvailable(enabled: Boolean, editable: Boolean, hasUsableWidget: Boolean): Boolean =
        enabled && (editable || hasUsableWidget)

    /** 槽位中是否至少有一个提供方公开且绑定有效的小组件。 */
    fun hasUsableWidget(
        slots: List<String?>,
        providerPackageOf: (String) -> String?,
        isPackagePublic: (String) -> Boolean,
        isBindingUsable: (String) -> Boolean,
    ): Boolean = slots.any { raw ->
        if (raw.isNullOrBlank()) return@any false
        val pkg = providerPackageOf(raw) ?: return@any false
        isPackagePublic(pkg) && isBindingUsable(raw)
    }
}
