package com.flue.launcher.surface.config

/**
 * R06 表盘写时复制：表盘资源可以共用，但在一个模式里保存修改时，如果另一模式正选着这个表盘，
 * 必须另存为新表盘，不能静默改掉另一模式的显示。
 */
object WatchFaceCopyOnWrite {
    fun mustCopy(editingLogicalId: String?, otherModeSelectedId: String?): Boolean =
        !editingLogicalId.isNullOrBlank() && editingLogicalId == otherModeSelectedId
}
