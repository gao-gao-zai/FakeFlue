package com.flue.launcher.surface

import com.flue.launcher.surface.tools.calculator.CalcError
import com.flue.launcher.surface.tools.calculator.CalcKey
import com.flue.launcher.surface.tools.calculator.CalcOutcome
import com.flue.launcher.surface.tools.calculator.CalculatorEngine
import com.flue.launcher.surface.tools.calculator.CalculatorInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** R31 计算器：求值纯函数与输入编辑。 */
class CalculatorTest {

    private fun value(expression: String): String {
        val outcome = CalculatorEngine.evaluate(expression)
        assertTrue("$expression → $outcome", outcome is CalcOutcome.Value)
        return CalculatorEngine.format((outcome as CalcOutcome.Value).value)
    }

    private fun error(expression: String): CalcError =
        (CalculatorEngine.evaluate(expression) as CalcOutcome.Error).kind

    @Test
    fun arithmeticFollowsPrecedence() {
        assertEquals("7", value("1+2×3"))
        assertEquals("9", value("(1+2)×3"))
        assertEquals("2.5", value("5÷2"))
        assertEquals("−1", value("2−3"))
        assertEquals("14", value("2*(3+4)"))
        assertEquals("4", value("10-3*2"))
        assertEquals("0.3", value("0.1+0.2"))
        assertEquals("0.333333333333", value("1÷3"))
        assertEquals("1", value("1÷3×3"))
    }

    @Test
    fun unaryPercentAndImplicitMultiplication() {
        assertEquals("−6", value("−2×3"))
        assertEquals("6", value("−2×−3"))
        assertEquals("−5", value("−(2+3)"))
        assertEquals("0.5", value("50%"))
        assertEquals("200.1", value("200+10%"))
        assertEquals("25", value("50%×50"))
        assertEquals("6", value("2(3)"))
        assertEquals("20", value("(2+2)(5)"))
        assertEquals("3", value("3."))
    }

    @Test
    fun unclosedParenthesesAreClosed() {
        assertEquals("9", value("(1+2)×(3"))
        assertEquals("5", value("((2+3"))
    }

    @Test
    fun errorsAreReportedNotThrown() {
        assertEquals(CalcError.DIVIDE_BY_ZERO, error("1÷0"))
        assertEquals(CalcError.DIVIDE_BY_ZERO, error("5÷(2−2)"))
        assertEquals(CalcError.SYNTAX, error("1+"))
        assertEquals(CalcError.SYNTAX, error("×2"))
        assertEquals(CalcError.SYNTAX, error("()"))
        assertEquals(CalcError.SYNTAX, error("1..2"))
        assertEquals(CalcError.SYNTAX, error(""))
        assertEquals(CalcError.SYNTAX, error("2)"))
        assertEquals(CalcError.OVERFLOW, error("1" + "0".repeat(60) + "×1" + "0".repeat(60)))
    }

    @Test
    fun formatUsesScientificForExtremes() {
        assertEquals("1E12", CalculatorEngine.format(BigDecimal("1000000000000")))
        assertEquals("1E-10", CalculatorEngine.format(BigDecimal("0.0000000001")))
        assertEquals("0", CalculatorEngine.format(BigDecimal("-0.000")))
        assertEquals("123456.789", CalculatorEngine.format(BigDecimal("123456.789")))
    }

    private fun type(vararg keys: CalcKey): CalculatorInput = keys.fold(CalculatorInput()) { acc, k -> acc.press(k) }

    private fun d(c: Char) = CalcKey.Digit(c)
    private fun op(c: Char) = CalcKey.Operator(c)

    @Test
    fun inputEditing() {
        assertEquals("12.5", type(d('1'), d('2'), CalcKey.Dot, CalcKey.Dot, d('5')).expression)
        assertEquals("0.5", type(CalcKey.Dot, d('5')).expression)
        assertEquals("7", type(d('0'), d('7')).expression)
        // 连按运算符替换上一个。
        assertEquals("3×", type(d('3'), op('+'), op('×')).expression)
        assertEquals("3×−", type(d('3'), op('×'), op('−')).expression)
        assertEquals("3+", type(d('3'), op('×'), op('−'), op('+')).expression)
        // 开头只能输负号。
        assertEquals("", type(op('×')).expression)
        assertEquals("−", type(op('−')).expression)
        // 右括号、百分号后接数字按乘法。
        assertEquals("(2)×3", type(CalcKey.Open, d('2'), CalcKey.Close, d('3')).expression)
        assertEquals("2×(", type(d('2'), CalcKey.Open).expression)
        // 没有未闭合的括号时不能输右括号。
        assertEquals("2", type(d('2'), CalcKey.Close).expression)
        assertEquals("12", type(d('1'), d('2'), d('3'), CalcKey.Backspace).expression)
        assertEquals("", type(d('1'), op('+'), CalcKey.Clear).expression)
        assertEquals("50%", type(d('5'), d('0'), CalcKey.Percent, CalcKey.Percent).expression)
    }

    @Test
    fun negateTogglesLastOperand() {
        assertEquals("−5", type(d('5'), CalcKey.Negate).expression)
        assertEquals("5", type(d('5'), CalcKey.Negate, CalcKey.Negate).expression)
        assertEquals("3−5", type(d('3'), op('+'), d('5'), CalcKey.Negate).expression)
        assertEquals("3+5", type(d('3'), op('−'), d('5'), CalcKey.Negate).expression)
        assertEquals("3×−5", type(d('3'), op('×'), d('5'), CalcKey.Negate).expression)
        assertEquals("−(1+2)", type(CalcKey.Open, d('1'), op('+'), d('2'), CalcKey.Close, CalcKey.Negate).expression)
        assertEquals("−", type(CalcKey.Negate).expression)
    }

    @Test
    fun equalsAndContinuation() {
        val done = type(d('6'), op('÷'), d('4'), CalcKey.Equals)
        assertTrue(done.fresh)
        assertEquals("1.5", CalculatorEngine.format((done.result as CalcOutcome.Value).value))
        // 接着结果算。
        assertEquals("1.5×", done.press(op('×')).expression)
        // 输数字开始新算式。
        assertEquals("8", done.press(d('8')).expression)
        assertEquals("−1.5", done.press(CalcKey.Negate).expression)
        // 除零：保留算式、显示错误，可以继续改。
        val bad = type(d('1'), op('÷'), d('0'), CalcKey.Equals)
        assertEquals(CalcOutcome.Error(CalcError.DIVIDE_BY_ZERO), bad.result)
        assertEquals("1÷", bad.press(CalcKey.Backspace).expression)
        assertNull(bad.press(CalcKey.Backspace).result)
    }

    @Test
    fun previewShowsWhileTyping() {
        assertNull(type(d('5')).preview)
        assertEquals(BigDecimal("8"), type(d('5'), op('+'), d('3')).preview?.value?.stripTrailingZeros()?.let { BigDecimal(it.toPlainString()) })
        assertNull(type(d('5'), op('÷'), d('0')).preview)
    }
}
