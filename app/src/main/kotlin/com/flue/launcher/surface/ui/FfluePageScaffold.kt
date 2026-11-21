package com.flue.launcher.surface.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableStateOf
import com.flue.launcher.surface.secret.BackDragResult
import com.flue.launcher.surface.secret.SafeBounds
import com.flue.launcher.surface.secret.backAreaGesture
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.surface.display.DisplayAdaptation
import com.flue.launcher.surface.display.SafeAreaFrame
import com.flue.launcher.surface.display.SafeAreaGeometry
import com.flue.launcher.surface.display.SafeAreaSpec
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.settings.miuix.MiuixIcons
import com.flue.launcher.ui.style.flueOverscroll
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 当前生效的安全区：已校准值，否则按屏幕形状取默认。 */
@Composable
fun currentSafeAreaSpec(): SafeAreaSpec {
    val context = LocalContext.current
    val stored by remember(context) { DisplayAdaptation.safeArea(context) }.collectAsState()
    return stored ?: SafeAreaSpec.default(isFlueScreenRound())
}

/** 安全区在给定页面尺寸上的位置，单位 dp（数值）。 */
@Composable
fun rememberSafeAreaFrame(width: Dp, height: Dp, spec: SafeAreaSpec = currentSafeAreaSpec()): SafeAreaFrame =
    remember(width, height, spec) { SafeAreaGeometry.frame(width.value, height.value, spec) }

/**
 * 右滑返回（R25）。只认横向为主的右滑；纵向滚动、滑条、文字选择等子控件先消费了事件就放弃。
 * 页面跟手右移，松手超过三分之一宽或速度够快就返回，否则弹回。
 */
fun Modifier.fflueSwipeBack(enabled: Boolean = true, onBack: () -> Unit): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val latestOnBack by rememberUpdatedState(onBack)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dx = 0f
                var dy = 0f
                var claimed = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.positionChange()
                    if (!claimed) {
                        if (change.isConsumed) return@awaitEachGesture
                        dx += delta.x
                        dy += delta.y
                        if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) return@awaitEachGesture
                        if (dx < -touchSlop) return@awaitEachGesture
                        if (dx > touchSlop && dx > abs(dy) * 1.5f) {
                            claimed = true
                            change.consume()
                        }
                    } else {
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val next = (offset.value + delta.x).coerceAtLeast(0f)
                        scope.launch { offset.snapTo(next) }
                    }
                }
                if (!claimed) return@awaitEachGesture
                val velocity = tracker.calculateVelocity().x
                val commit = offset.value > size.width / 3f || (velocity > 900f && offset.value > touchSlop)
                scope.launch {
                    if (commit) {
                        latestOnBack()
                        // 返回被拦下（例如忙碌中）时页面还在，弹回原位。
                        delay(450)
                    }
                    offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                }
            }
        }
        .graphicsLayer { translationX = offset.value }
}

/**
 * FFlue 新页面骨架（R23、R25）：沿用 Flue 主题，紧凑返回按钮，标题随内容滚动，只有一个滚动层，
 * 右滑返回；普通内容按安全区宽高留边，可选的底部固定操作栏再按圆角内移收窄。
 * 列表项之间的空隙属于同一个 LazyColumn，从空隙起手也能滚动。
 */
@Composable
fun FfluePageScaffold(
    stateKey: Any,
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    swipeBackEnabled: Boolean = true,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    /** 要跳到的列表位置（第 0 项是页头）；跳完调用 [onScrolled]，调用方把它清成 null。 */
    scrollTo: Int? = null,
    onScrolled: () -> Unit = {},
    /**
     * 返回按钮按住 2 秒后拖动的结果（R15、R17，只有公开关于页使用）。为 null 时返回按钮只是普通按钮。
     * 拖动是否达标按本页安全区判断。
     */
    onBackAreaDrag: ((BackDragResult) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val style = LauncherTheme.style
    val state = remember(stateKey) { LazyListState() }
    val reveal = remember(stateKey) { Animatable(0f) }
    LaunchedEffect(stateKey) {
        state.scrollToItem(0)
        reveal.animateTo(1f, tween(240))
    }
    val scrolled by rememberUpdatedState(onScrolled)
    LaunchedEffect(scrollTo) {
        val target = scrollTo ?: return@LaunchedEffect
        state.animateScrollToItem(target)
        scrolled()
    }
    val system = WindowInsets.safeDrawing.asPaddingValues()
    val density = LocalDensity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    var pageCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Surface(
        modifier = Modifier.fillMaxSize().fflueSwipeBack(swipeBackEnabled, onBack),
        color = style.screenBackground,
        contentColor = style.bodyColor,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { pageCoordinates = it }) {
            val frame = rememberSafeAreaFrame(maxWidth, maxHeight)
            val backArea = onBackAreaDrag?.let { onDrag ->
                BackAreaHandler(onDrag) {
                    val page = pageCoordinates?.takeIf { it.isAttached } ?: return@BackAreaHandler null
                    val origin = page.positionInWindow()
                    val scale = density.density
                    SafeBounds(
                        left = origin.x + frame.left * scale,
                        top = origin.y + frame.top * scale,
                        width = frame.width * scale,
                        height = frame.height * scale,
                    )
                }
            }
            val rounded = frame.barInset > 4f
            val edgeTop = (frame.top + frame.barInset).dp
            val horizontal = frame.left.dp + 12.dp
            val barSpace = if (bottomBar != null) with(density) { barHeightPx.toDp() } + 8.dp else 0.dp
            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = reveal.value
                        translationY = (1f - reveal.value) * 14.dp.toPx()
                    }
                    .flueOverscroll(state),
                contentPadding = PaddingValues(
                    start = horizontal,
                    end = horizontal,
                    top = maxOf(system.calculateTopPadding(), edgeTop) + 8.dp,
                    bottom = maxOf(system.calculateBottomPadding(), edgeTop) + 16.dp + barSpace,
                ),
                verticalArrangement = Arrangement.spacedBy(if (style.uiStyle == UiStyle.APPLE_WATCH) 16.dp else 12.dp),
            ) {
                item(key = "fflue_page_header") {
                    FflueHeader(title = title, subtitle = subtitle, centered = rounded, onBack = onBack, backArea = backArea)
                }
                content()
            }
            if (bottomBar != null) {
                val barSide = (frame.left + frame.barInset).dp + 8.dp
                val barBottom = maxOf(system.calculateBottomPadding(), edgeTop) + 6.dp
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.35f to style.screenBackground,
                                1f to style.screenBackground,
                            ),
                        )
                        .padding(start = barSide, end = barSide, top = 14.dp, bottom = barBottom),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().onSizeChanged { barHeightPx = it.height },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = bottomBar,
                    )
                }
            }
        }
    }
}

@Composable
private fun FflueHeader(
    title: String,
    subtitle: String?,
    centered: Boolean,
    onBack: () -> Unit,
    backArea: BackAreaHandler? = null,
) {
    val style = LauncherTheme.style
    val roundScreen = isFlueScreenRound()
    val inlineTitle = style.uiStyle == UiStyle.MIUIX && !centered
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (centered) {
                Arrangement.Center
            } else {
                Arrangement.spacedBy(12.dp)
            },
        ) {
            FflueBackButton(onBack, backArea)
            if (inlineTitle) Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = style.titleColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
        }
        if (!inlineTitle) Text(
            text = title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            color = if (style.uiStyle == UiStyle.APPLE_WATCH) style.accentColor else style.titleColor,
            fontSize = if (style.uiStyle == UiStyle.APPLE_WATCH) 28.sp else 30.sp,
            fontWeight = if (style.uiStyle == UiStyle.APPLE_WATCH) FontWeight.Bold else FontWeight.Medium,
            textAlign = if (centered || roundScreen || style.uiStyle == UiStyle.APPLE_WATCH) {
                TextAlign.Center
            } else {
                TextAlign.Start
            },
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = style.secondaryTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                textAlign = if (centered || roundScreen) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/** 返回区域的隐藏拖动（R15、R17）：拖动结果和安全区在窗口中的位置。 */
class BackAreaHandler(val onDrag: (BackDragResult) -> Unit, val safeBounds: () -> SafeBounds?)

/**
 * 紧凑返回按钮：点击就执行返回（R25），不是装饰箭头。
 * 带 [backArea] 时改用自己的手势：点击返回，按住 2 秒后拖动交给 [BackAreaHandler]，外观和点击行为不变。
 */
@Composable
fun FflueBackButton(onClick: () -> Unit, backArea: BackAreaHandler? = null) {
    val style = LauncherTheme.style
    val source = remember { MutableInteractionSource() }
    val clickPressed by source.collectIsPressedAsState()
    var holdPressed by remember { mutableStateOf(false) }
    val pressed = clickPressed || holdPressed
    val description = stringResource(R.string.watch_editor_back)
    val input = if (backArea == null) {
        Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
    } else {
        Modifier
            .semantics {
                role = Role.Button
                onClick { onClick(); true }
            }
            .backAreaGesture(
                onTap = onClick,
                onDrag = backArea.onDrag,
                safeBounds = backArea.safeBounds,
                onPressedChange = { holdPressed = it },
            )
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "fflue_back_scale",
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(if (style.uiStyle == UiStyle.APPLE_WATCH) CircleShape else style.smallControlShape)
            .background(style.topBarChipColor)
            .then(input),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = description,
            tint = style.titleColor,
            modifier = Modifier.size(22.dp),
        )
    }
}
