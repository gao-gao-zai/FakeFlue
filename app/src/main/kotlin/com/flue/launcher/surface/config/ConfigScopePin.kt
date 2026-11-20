package com.flue.launcher.surface.config

import com.flue.launcher.surface.mode.ConfigScope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 把一次异步写入固定到提交时的配置模式（R06）。
 *
 * 设置写入常常排队或防抖后才执行；如果期间切换了模式，[ModeScopedDataStore] 按执行时的模式落盘
 * 就会把旧模式的修改写进新模式。提交方把这个元素放进协程上下文，写入就落回提交时的模式。
 */
class ConfigScopePin(val scope: ConfigScope) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConfigScopePin>
}
