package com.flue.launcher.surface.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.surface.display.AppDensity
import com.flue.launcher.surface.display.DisplayAdaptation
import com.flue.launcher.surface.display.SafeAreaGeometry
import com.flue.launcher.surface.display.SafeAreaSpec
import com.flue.launcher.ui.common.isFlueScreenRound
import com.flue.launcher.ui.settings.agent.AgentSettingsEntry
import com.flue.launcher.ui.settings.agent.AgentSettingsGroup
import com.flue.launcher.ui.style.FlueButton
import com.flue.launcher.ui.style.FlueOutlinedButton
import com.flue.launcher.ui.style.FlueSlider
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.LocalGlobalUiScale
import kotlin.math.roundToInt

/** 显示与外观 → 显示适配 → 屏幕适配（R25 入口位置）。伪装桌面管理也进同一页。 */
@Composable
internal fun ScreenFitPage(
    onBack: () -> Unit,
    onOpenSafeArea: () -> Unit,
    onOpenAppDpi: () -> Unit,
) {
    val context = LocalContext.current
    val spec = currentSafeAreaSpec()
    val dpi = remember(context) { DisplayAdaptation.effectiveDpi(context) }
    FfluePageScaffold(
        stateKey = "screen_fit",
        title = stringResource(R.string.screen_fit_title),
        subtitle = stringResource(R.string.screen_fit_subtitle),
        onBack = onBack,
    ) {
        item(key = "screen_fit_entries") {
            AgentSettingsGroup {
                AgentSettingsEntry(
                    title = stringResource(R.string.safe_area_title),
                    subtitle = stringResource(
                        R.string.safe_area_summary,
                        spec.widthPercent.formatTenth(),
                        spec.heightPercent.formatTenth(),
                        spec.cornerPercent.formatTenth(),
                    ),
                    onClick = onOpenSafeArea,
                )
                AgentSettingsEntry(
                    title = stringResource(R.string.app_dpi_title),
                    subtitle = stringResource(R.string.app_dpi_summary, dpi, DisplayAdaptation.stableDpi()),
                    onClick = onOpenAppDpi,
                    last = true,
                )
            }
        }
    }
}

private enum class SafeAreaDimension(val labelRes: Int, val range: ClosedFloatingPointRange<Float>) {
    WIDTH(R.string.safe_area_width, SafeAreaSpec.MIN_SIZE_PERCENT..SafeAreaSpec.MAX_SIZE_PERCENT),
    HEIGHT(R.string.safe_area_height, SafeAreaSpec.MIN_SIZE_PERCENT..SafeAreaSpec.MAX_SIZE_PERCENT),
    CORNER(R.string.safe_area_corner, SafeAreaSpec.MIN_CORNER_PERCENT..SafeAreaSpec.MAX_CORNER_PERCENT),
}

private fun SafeAreaSpec.valueOf(d: SafeAreaDimension): Float = when (d) {
    SafeAreaDimension.WIDTH -> widthPercent
    SafeAreaDimension.HEIGHT -> heightPercent
    SafeAreaDimension.CORNER -> cornerPercent
}

private fun SafeAreaSpec.with(d: SafeAreaDimension, value: Float): SafeAreaSpec = when (d) {
    SafeAreaDimension.WIDTH -> copy(widthPercent = value)
    SafeAreaDimension.HEIGHT -> copy(heightPercent = value)
    SafeAreaDimension.CORNER -> copy(cornerPercent = value)
}.normalized()

/**
 * 安全区校准（R23）：全屏实时画出草稿边界和像素尺寸；操作区固定在屏幕中央、尺寸不随草稿变化，
 * 草稿再小也能保存或取消。只有点保存才写入，返回、右滑和取消都不改原值。
 */
@Composable
internal fun SafeAreaCalibrationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val style = LauncherTheme.style
    val original = currentSafeAreaSpec()
    val roundScreen = isFlueScreenRound()
    var draft by remember(original) { mutableStateOf(original) }
    var dimension by rememberSaveable { mutableStateOf(SafeAreaDimension.WIDTH) }
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(style.screenBackground)
            .fflueSwipeBack(onBack = onBack),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val framePx = SafeAreaGeometry.frame(widthPx, heightPx, draft)
        val accent = style.accentColor
        Canvas(Modifier.fillMaxSize()) {
            val rect = RoundRect(
                left = framePx.left,
                top = framePx.top,
                right = framePx.right,
                bottom = framePx.bottom,
                cornerRadius = CornerRadius(framePx.cornerRadius),
            )
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(size.width, size.height)))
                addRoundRect(rect)
            }
            drawPath(outside, accent.copy(alpha = 0.22f))
            drawPath(Path().apply { addRoundRect(rect) }, accent, style = Stroke(width = 2.dp.toPx()))
        }
        // 中央操作区：取屏幕尺寸的固定比例，和草稿无关。
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = maxWidth * 0.72f, height = maxHeight * 0.72f)
                .clip(style.cardShape)
                .background(style.cardColor)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.safe_area_title),
                color = style.titleColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = stringResource(
                    R.string.safe_area_pixels,
                    framePx.width.roundToInt(),
                    framePx.height.roundToInt(),
                    framePx.cornerRadius.roundToInt(),
                ),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SafeAreaDimension.entries.forEach { d ->
                    val selected = d == dimension
                    Text(
                        text = stringResource(d.labelRes),
                        color = if (selected) style.screenBackground else style.bodyColor,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(style.smallControlShape)
                            .background(if (selected) accent else style.topBarChipColor)
                            .clickable { dimension = d }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            NumberStepper(
                valueText = "${draft.valueOf(dimension).formatTenth()}%",
                onMinus = { draft = draft.with(dimension, draft.valueOf(dimension) - 0.1f) },
                onPlus = { draft = draft.with(dimension, draft.valueOf(dimension) + 0.1f) },
            )
            FlueSlider(
                value = draft.valueOf(dimension),
                onValueChange = { draft = draft.with(dimension, it) },
                valueRange = dimension.range,
                modifier = Modifier.fillMaxWidth(),
            )
            ExactInput(
                key = dimension to draft.valueOf(dimension),
                initial = draft.valueOf(dimension).formatTenth(),
                keyboardType = KeyboardType.Decimal,
                rangeText = "${dimension.range.start.formatTenth()}–${dimension.range.endInclusive.formatTenth()}",
                parse = { SafeAreaSpec.parsePercent(it, dimension.range) },
                onApply = { draft = draft.with(dimension, it) },
            )
            Text(
                text = stringResource(R.string.safe_area_hint),
                color = style.secondaryTextColor,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            FlueOutlinedButton(
                onClick = { draft = SafeAreaSpec.default(roundScreen) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.display_restore_default), fontSize = 12.sp) }
            SaveCancelRow(
                onCancel = onBack,
                onSave = {
                    if (DisplayAdaptation.saveSafeArea(context, draft)) {
                        onBack()
                    } else {
                        Toast.makeText(context, R.string.display_save_failed, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

/**
 * 应用 DPI 校准（R24）：操作区固定按设备稳定密度排版，草稿只作用于预览框里的真实 Flue 控件。
 * 保存后重建已打开的页面；取消不保存。
 */
@Composable
internal fun AppDpiCalibrationPage(onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val stable = DisplayAdaptation.stableDpi()
    val current = remember(context) { DisplayAdaptation.effectiveDpi(context) }
    var draft by rememberSaveable { mutableIntStateOf(current) }
    val base = LocalDensity.current
    val globalScale = LocalGlobalUiScale.current
    val stableDensity = remember(base.fontScale, stable) { Density(stable / 160f, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides stableDensity) {
        FfluePageScaffold(
            stateKey = "app_dpi",
            title = stringResource(R.string.app_dpi_title),
            subtitle = stringResource(R.string.app_dpi_hint),
            onBack = onBack,
            bottomBar = {
                FlueOutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.display_cancel), fontSize = 13.sp)
                }
                FlueButton(
                    onClick = {
                        when {
                            draft == current -> onBack()
                            DisplayAdaptation.saveDpi(context, draft) -> onSaved()
                            else -> Toast.makeText(context, R.string.display_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.display_save), fontSize = 13.sp) }
            },
        ) {
            item(key = "app_dpi_preview") {
                DpiPreview(draft = draft, globalScale = globalScale, fontScale = base.fontScale)
            }
            item(key = "app_dpi_value") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NumberStepper(
                        valueText = stringResource(R.string.app_dpi_value, draft),
                        onMinus = { draft = AppDensity.clamp(draft - 1) },
                        onPlus = { draft = AppDensity.clamp(draft + 1) },
                    )
                    Text(
                        text = stringResource(R.string.app_dpi_summary, current, stable),
                        color = LauncherTheme.style.secondaryTextColor,
                        fontSize = 12.sp,
                    )
                    FlueSlider(
                        value = draft.toFloat(),
                        onValueChange = { draft = AppDensity.clamp(it.roundToInt()) },
                        valueRange = AppDensity.MIN_DPI.toFloat()..AppDensity.MAX_DPI.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ExactInput(
                        key = draft,
                        initial = draft.toString(),
                        keyboardType = KeyboardType.Number,
                        rangeText = "${AppDensity.MIN_DPI}–${AppDensity.MAX_DPI}",
                        parse = AppDensity::parseInput,
                        onApply = { draft = it },
                    )
                    FlueOutlinedButton(onClick = { draft = stable }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.display_restore_default), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DpiPreview(draft: Int, globalScale: Float, fontScale: Float) {
    val style = LauncherTheme.style
    val previewDensity = remember(draft, globalScale, fontScale) { Density(draft / 160f * globalScale, fontScale) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.app_dpi_preview),
            color = style.secondaryTextColor,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(style.cardShape)
                .border(1.dp, style.outlineColor, style.cardShape)
                .background(style.screenBackground),
        ) {
            CompositionLocalProvider(LocalDensity provides previewDensity) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentSettingsGroup {
                        AgentSettingsEntry(
                            title = stringResource(R.string.app_dpi_preview_item),
                            subtitle = stringResource(R.string.app_dpi_preview_item_sub),
                            onClick = {},
                            last = true,
                        )
                    }
                    FlueButton(onClick = {}) { Text(stringResource(R.string.app_dpi_preview_button)) }
                }
            }
        }
    }
}

@Composable
private fun NumberStepper(valueText: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val style = LauncherTheme.style
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepButton("−", onMinus)
        Text(valueText, color = style.titleColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val style = LauncherTheme.style
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(style.smallControlShape)
            .background(style.topBarChipColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = style.titleColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 精确数值输入：按完成键生效，非法值标红并保留原值。外部值变化时同步显示。 */
@Composable
private fun <T> ExactInput(
    key: Any,
    initial: String,
    keyboardType: KeyboardType,
    rangeText: String,
    parse: (String) -> T?,
    onApply: (T) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        text = initial
        error = false
    }
    fun apply() {
        val parsed = parse(text)
        if (parsed == null) error = true else onApply(parsed)
    }
    FlueTextField(
        value = text,
        onValueChange = {
            text = it
            error = false
        },
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.display_exact_value),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { apply() }),
        supportingText = if (error) stringResource(R.string.display_input_invalid, rangeText) else null,
        isError = error,
        trailingIcon = {
            Text(
                text = "OK",
                color = LauncherTheme.style.accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { apply() }.padding(8.dp),
            )
        },
    )
}

@Composable
private fun SaveCancelRow(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FlueOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.display_cancel), fontSize = 12.sp, maxLines = 1)
        }
        FlueButton(onClick = onSave, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.display_save), fontSize = 12.sp, maxLines = 1)
        }
    }
}

internal fun Float.formatTenth(): String {
    val v = SafeAreaSpec.tenth(this)
    return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}
