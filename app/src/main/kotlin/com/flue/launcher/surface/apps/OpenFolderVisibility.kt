package com.flue.launcher.surface.apps

/**
 * 公开白名单变化后，已经打开的文件夹怎么处理（R10）。
 *
 * 打开文件夹时内容是快照，桌面跨应用启动也会保留它；白名单收窄后不刷新就会继续显示
 * 已不公开应用的名称和图标。这里按重新筛选后的结果决定：文件夹整个不可见或没有可见子项就关掉，
 * 否则换成筛选后的文件夹条目（预览图也随之重建）和子项。
 */
object OpenFolderVisibility {
    sealed interface Update<out T> {
        data object Close : Update<Nothing>
        data class Show<T>(val folder: T, val items: List<T>) : Update<T>
    }

    fun <T> update(visibleFolder: T?, visibleItems: List<T>): Update<T> =
        if (visibleFolder == null || visibleItems.isEmpty()) Update.Close else Update.Show(visibleFolder, visibleItems)
}
