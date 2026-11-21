package com.flue.launcher.surface.comm

import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flue.launcher.FlueApplication
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel

internal val CommGreen = Color(0xFF34C759)
internal val CommRed = Color(0xFFFF3B30)

/** 电话、短信、角色恢复页的共同基类：沿用 Flue 主题、全局缩放和屏幕形状。 */
abstract class CommActivity : FlueComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    protected fun setCommContent(content: @Composable () -> Unit) {
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val scale by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(scale) { content() }
            }
        }
    }
}

/**
 * 来电界面要尽快出现（进程可能刚被系统为通话启动）：只用进程级主题状态，不创建桌面 ViewModel。
 */
abstract class LightCommActivity : FlueComponentActivity() {
    protected fun setLightContent(content: @Composable () -> Unit) {
        val shared = FlueApplication.repositories(this).sharedState
        setContent {
            val themeMode by shared.themeMode.collectAsStateWithLifecycle()
            val uiStyle by shared.uiStyle.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle) { content() }
        }
    }
}

/** 圆形操作键（接听、挂断、拨号）。 */
@Composable
internal fun CommRoundButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    size: Dp = 52.dp,
    enabled: Boolean = true,
    showLabel: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (enabled) color else color.copy(alpha = 0.35f))
                .semantics { contentDescription = label }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.46f))
        }
        if (showLabel) Text(label, color = LauncherTheme.style.secondaryTextColor, fontSize = 10.sp, maxLines = 1)
    }
}

/** 小号开关键（静音、免提、键盘）。 */
@Composable
internal fun CommToggle(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val style = LauncherTheme.style
    Box(
        Modifier
            .clip(style.compactShape)
            .background(if (checked) style.accentColor else style.cardColor)
            .clickable(enabled = enabled, onClick = onClick)
            .size(width = 58.dp, height = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> style.secondaryTextColor
                checked -> Color.Black
                else -> style.titleColor
            },
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/** 拨号盘：1–9、*、0、#。[onLongZero] 输入 +。 */
@Composable
internal fun CommDialPad(onKey: (Char) -> Unit, onLongZero: (() -> Unit)? = null) {
    val style = LauncherTheme.style
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c ->
                    Box(
                        Modifier
                            .size(width = 52.dp, height = 38.dp)
                            .clip(style.compactShape)
                            .background(style.cardColor)
                            .then(
                                if (c == '0' && onLongZero != null) {
                                    Modifier.combinedClickableCompat(onClick = { onKey(c) }, onLongClick = onLongZero)
                                } else {
                                    Modifier.clickable { onKey(c) }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(c.toString(), color = style.titleColor, fontSize = 19.sp, maxLines = 1)
                            if (c == '0' && onLongZero != null) Text("+", color = style.secondaryTextColor, fontSize = 8.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
internal fun CommSpacer(width: Dp) = Spacer(Modifier.width(width))
