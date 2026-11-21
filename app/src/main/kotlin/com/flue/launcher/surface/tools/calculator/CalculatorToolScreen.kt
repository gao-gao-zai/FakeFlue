package com.flue.launcher.surface.tools.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.R
import com.flue.launcher.surface.tools.TABULAR
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.style.FlueSurface
import com.flue.launcher.ui.theme.LauncherTheme

private enum class KeyKind { DIGIT, FUNCTION, OPERATOR, EQUALS }

private data class KeySpec(val label: String, val key: CalcKey, val kind: KeyKind, val descriptionRes: Int? = null)

private val ROWS: List<List<KeySpec>> = listOf(
    listOf(
        KeySpec("C", CalcKey.Clear, KeyKind.FUNCTION, R.string.surface_tool_calc_clear),
        KeySpec("(", CalcKey.Open, KeyKind.FUNCTION),
        KeySpec(")", CalcKey.Close, KeyKind.FUNCTION),
        KeySpec("÷", CalcKey.Operator('÷'), KeyKind.OPERATOR),
    ),
    listOf(digit('7'), digit('8'), digit('9'), KeySpec("×", CalcKey.Operator('×'), KeyKind.OPERATOR)),
    listOf(digit('4'), digit('5'), digit('6'), KeySpec("−", CalcKey.Operator('−'), KeyKind.OPERATOR)),
    listOf(digit('1'), digit('2'), digit('3'), KeySpec("+", CalcKey.Operator('+'), KeyKind.OPERATOR)),
    listOf(
        KeySpec("±", CalcKey.Negate, KeyKind.FUNCTION, R.string.surface_tool_calc_negate),
        digit('0'),
        KeySpec(".", CalcKey.Dot, KeyKind.DIGIT),
        KeySpec("%", CalcKey.Percent, KeyKind.FUNCTION),
    ),
    listOf(
        KeySpec("⌫", CalcKey.Backspace, KeyKind.FUNCTION, R.string.surface_tool_calc_backspace),
        KeySpec("=", CalcKey.Equals, KeyKind.EQUALS),
    ),
)

private fun digit(c: Char) = KeySpec(c.toString(), CalcKey.Digit(c), KeyKind.DIGIT)

/** 计算器页（R31）：求值是纯函数 [CalculatorEngine]，错误显示文字不崩溃。 */
@Composable
internal fun CalculatorToolScreen(onBack: () -> Unit) {
    var expression by rememberSaveable { mutableStateOf("") }
    var fresh by rememberSaveable { mutableStateOf(false) }
    var input by remember { mutableStateOf(restore(expression, fresh)) }

    fun press(key: CalcKey) {
        input = input.press(key)
        expression = input.expression
        fresh = input.fresh
    }

    FfluePageScaffold(stateKey = "tool_calculator", title = stringResource(R.string.surface_tool_calculator_title), onBack = onBack) {
        item(key = "display") { CalculatorDisplay(input) }
        ROWS.forEachIndexed { index, row ->
            item(key = "row_$index") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { spec -> CalcButton(spec) { press(spec.key) } }
                }
            }
        }
    }
}

/** 旋转、深色切换等重建后恢复：算式原样恢复，已出结果的重新求值。 */
private fun restore(expression: String, fresh: Boolean): CalculatorInput {
    if (expression.isEmpty()) return CalculatorInput()
    if (!fresh) return CalculatorInput(expression)
    val outcome = CalculatorEngine.evaluate(expression)
    return if (outcome is CalcOutcome.Value) CalculatorInput(expression, outcome, fresh = true) else CalculatorInput(expression)
}

@Composable
private fun errorText(kind: CalcError): String = stringResource(
    when (kind) {
        CalcError.SYNTAX -> R.string.surface_tool_calc_error_syntax
        CalcError.DIVIDE_BY_ZERO -> R.string.surface_tool_calc_error_divide
        CalcError.OVERFLOW -> R.string.surface_tool_calc_error_overflow
    },
)

@Composable
private fun CalculatorDisplay(input: CalculatorInput) {
    val style = LauncherTheme.style
    val result = input.result
    val main: String
    val secondary: String?
    var error = false
    when {
        input.fresh && result is CalcOutcome.Value -> {
            main = CalculatorEngine.format(result.value)
            secondary = input.expression
        }
        result is CalcOutcome.Error -> {
            main = input.expression
            secondary = errorText(result.kind)
            error = true
        }
        else -> {
            main = input.expression.ifEmpty { "0" }
            secondary = input.preview?.let { "= " + CalculatorEngine.format(it.value) }
        }
    }
    val mainSize = when {
        main.length > 20 -> 18.sp
        main.length > 12 -> 22.sp
        else -> 32.sp
    }
    FlueSurface(modifier = Modifier.fillMaxWidth(), shape = style.cardShape, border = null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = secondary ?: " ",
                color = if (error) Color(0xFFFF6B6B) else style.secondaryTextColor,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = main,
                color = style.titleColor,
                fontSize = mainSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                style = TABULAR,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RowScope.CalcButton(spec: KeySpec, onClick: () -> Unit) {
    val style = LauncherTheme.style
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val accent = spec.kind == KeyKind.OPERATOR || spec.kind == KeyKind.EQUALS
    val base = when (spec.kind) {
        KeyKind.EQUALS -> style.accentColor
        KeyKind.OPERATOR -> style.accentColor.copy(alpha = 0.28f)
        KeyKind.FUNCTION -> style.topBarChipColor
        KeyKind.DIGIT -> style.cardColor
    }
    val textColor = when {
        spec.kind == KeyKind.EQUALS -> if (style.accentColor.luminance() > 0.55f) Color.Black else Color.White
        accent -> style.accentColor
        else -> style.titleColor
    }
    val description = spec.descriptionRes?.let { stringResource(it) }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(style.compactShape)
            .background(if (pressed) style.pressedCardColor else base)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(spec.label, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}
