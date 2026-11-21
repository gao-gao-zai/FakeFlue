package com.flue.launcher.surface.tools

import android.content.Context
import com.flue.launcher.surface.tools.alert.AndroidToolNotifier
import com.flue.launcher.surface.tools.alert.AndroidToolScheduler

/** 进程内唯一的 [ToolAlertsEngine]。页面、接收器和应用启动复算共用它。 */
object ToolAlerts {
    @Volatile
    private var instance: ToolAlertsEngine? = null

    fun engine(context: Context): ToolAlertsEngine = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app ->
            ToolAlertsEngine(ToolStore(app), SystemToolClock(app), AndroidToolScheduler(app), AndroidToolNotifier(app))
        }.also { instance = it }
    }

    /** 进程启动时调用：从持久状态恢复未到期的登记，补发进程不在时到点的提醒（各只一次）。 */
    fun reconcile(context: Context) {
        runCatching { engine(context).reconcile() }
    }
}
