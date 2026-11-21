package com.flue.launcher.surface.setup

/** 授权按实际用途分组（R19、R28）。 */
enum class PermissionGroup { SYSTEM_CONTROL, PHONE_SMS, NOTIFICATIONS, FACE_DATA }

enum class PermissionItem(val group: PermissionGroup) {
    ROOT(PermissionGroup.SYSTEM_CONTROL),
    SHIZUKU(PermissionGroup.SYSTEM_CONTROL),
    ACCESSIBILITY(PermissionGroup.SYSTEM_CONTROL),
    OVERLAY(PermissionGroup.SYSTEM_CONTROL),
    WRITE_SETTINGS(PermissionGroup.SYSTEM_CONTROL),
    NOTIFICATION_LISTENER(PermissionGroup.NOTIFICATIONS),
    DND_ACCESS(PermissionGroup.NOTIFICATIONS),
    /** R31：闹钟、计时器到点通知。 */
    POST_NOTIFICATIONS(PermissionGroup.NOTIFICATIONS),
    /** R31：准点提醒（Android 12+）。 */
    EXACT_ALARM(PermissionGroup.NOTIFICATIONS),
    LOCATION(PermissionGroup.FACE_DATA),
    CALENDAR(PermissionGroup.FACE_DATA),
    ACTIVITY(PermissionGroup.FACE_DATA),
}

/**
 * 一项的真实状态。[UNCHECKED] 只用于 Root：没有用户操作就不探测（R28 不因展示统计请求 Root）。
 * [NOT_APPLICABLE] 表示本系统没有这一项，不计入总数。
 */
enum class PermissionStatus { GRANTED, DENIED, UNCHECKED, NOT_APPLICABLE }

data class GroupSummary(
    val group: PermissionGroup,
    val configured: Int,
    val total: Int,
    val configuredItems: List<PermissionItem>,
)

object PermissionSummary {
    fun of(statuses: Map<PermissionItem, PermissionStatus>): List<GroupSummary> =
        PermissionGroup.entries.map { group ->
            val applicable = PermissionItem.entries.filter {
                it.group == group && (statuses[it] ?: PermissionStatus.NOT_APPLICABLE) != PermissionStatus.NOT_APPLICABLE
            }
            val granted = applicable.filter { statuses[it] == PermissionStatus.GRANTED }
            GroupSummary(group, granted.size, applicable.size, granted)
        }
}
