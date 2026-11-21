package com.flue.launcher.surface.setup

/** 授权按实际用途分组（R19、R28）。 */
enum class PermissionGroup { SYSTEM_CONTROL, PHONE_SMS, NOTIFICATIONS, FACE_DATA }

enum class PermissionItem(val group: PermissionGroup) {
    ROOT(PermissionGroup.SYSTEM_CONTROL),
    SHIZUKU(PermissionGroup.SYSTEM_CONTROL),
    ACCESSIBILITY(PermissionGroup.SYSTEM_CONTROL),
    OVERLAY(PermissionGroup.SYSTEM_CONTROL),
    WRITE_SETTINGS(PermissionGroup.SYSTEM_CONTROL),
    /** R32：默认电话应用（来电、通话中界面）。 */
    DIALER_ROLE(PermissionGroup.PHONE_SMS),

    /** R32：默认短信应用（接收并写入短信）。 */
    SMS_ROLE(PermissionGroup.PHONE_SMS),

    /** 拨打电话、读取通话状态。 */
    PHONE_CALLS(PermissionGroup.PHONE_SMS),
    CALL_LOG(PermissionGroup.PHONE_SMS),

    /** 发送、读取、接收短信。 */
    SMS(PermissionGroup.PHONE_SMS),

    /** Android 13+：来电与短信通知。 */
    COMM_NOTIFICATIONS(PermissionGroup.PHONE_SMS),
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
