package com.flue.launcher.surface.tools.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** 计算结果。错误不抛异常，界面显示对应文字（R31：除零显示错误而不是崩溃）。 */
sealed interface CalcOutcome {
    data class Value(val value: BigDecimal) : CalcOutcome
    data class Error(val kind: CalcError) : CalcOutcome
}

enum class CalcError { SYNTAX, DIVIDE_BY_ZERO, OVERFLOW }

/**
 * 四则运算、括号、小数、正负号、百分号的求值（纯函数）。
 *
 * 运算符可写 `+ - * /` 或 `+ − × ÷`。`%` 是后缀，把前面的数除以 100（与 AOSP 计算器一致：
 * `200+10%` = 200.1）。数字或右括号后紧跟左括号或数字按乘法处理；末尾未闭合的括号自动补齐。
 */
object CalculatorEngine {
    private val CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)
    private val DISPLAY = MathContext(12, RoundingMode.HALF_UP)
    private val LIMIT = BigDecimal.ONE.scaleByPowerOfTen(100)
    private val HUNDRED = BigDecimal(100)

    fun evaluate(expression: String): CalcOutcome {
        val tokens = tokenize(expression) ?: return CalcOutcome.Error(CalcError.SYNTAX)
        if (tokens.isEmpty()) return CalcOutcome.Error(CalcError.SYNTAX)
        val open = tokens.count { it == Token.Open } - tokens.count { it == Token.Close }
        val balanced = if (open > 0) tokens + List(open) { Token.Close } else tokens
        return try {
            val parser = Parser(balanced)
            val value = parser.expression()
            if (!parser.atEnd()) CalcOutcome.Error(CalcError.SYNTAX) else CalcOutcome.Value(value)
        } catch (_: DivideByZero) {
            CalcOutcome.Error(CalcError.DIVIDE_BY_ZERO)
        } catch (_: Overflow) {
            CalcOutcome.Error(CalcError.OVERFLOW)
        } catch (_: SyntaxError) {
            CalcOutcome.Error(CalcError.SYNTAX)
        } catch (_: ArithmeticException) {
            CalcOutcome.Error(CalcError.OVERFLOW)
        }
    }

    /** 显示用：12 位有效数字，去掉末尾的 0；太大或太小用科学计数法。 */
    fun format(value: BigDecimal): String {
        if (value.signum() == 0) return "0"
        val rounded = value.round(DISPLAY).stripTrailingZeros()
        val abs = rounded.abs()
        val text = if (abs >= BigDecimal("1E12") || abs < BigDecimal("1E-9")) {
            abs.toString().replace("E+", "E")
        } else {
            abs.toPlainString()
        }
        // 只有数值本身的负号用显示用减号；指数里的负号保持 ASCII。
        return if (rounded.signum() < 0) "−$text" else text
    }

    /** 接着结果继续输入时使用的表达式文本（不丢精度、不用科学计数法）。 */
    fun continuation(value: BigDecimal): String {
        val plain = value.round(CONTEXT).stripTrailingZeros().toPlainString()
        return if (plain == "-0") "0" else plain.replace('-', '−')
    }

    private sealed interface Token {
        data class Num(val value: BigDecimal) : Token
        data object Plus : Token
        data object Minus : Token
        data object Times : Token
        data object Divide : Token
        data object Percent : Token
        data object Open : Token
        data object Close : Token
    }

    private fun tokenize(text: String): List<Token>? {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    val literal = text.substring(start, i)
                    if (literal.count { it == '.' } > 1 || literal == ".") return null
                    out += Token.Num(BigDecimal(if (literal.endsWith('.')) literal.dropLast(1) else literal))
                }
                else -> {
                    out += when (c) {
                        '+' -> Token.Plus
                        '-', '−' -> Token.Minus
                        '*', '×' -> Token.Times
                        '/', '÷' -> Token.Divide
                        '%' -> Token.Percent
                        '(' -> Token.Open
                        ')' -> Token.Close
                        else -> return null
                    }
                    i++
                }
            }
        }
        return out
    }

    private class SyntaxError : RuntimeException()
    private class DivideByZero : RuntimeException()
    private class Overflow : RuntimeException()

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun atEnd() = pos >= tokens.size

        private fun peek(): Token? = tokens.getOrNull(pos)

        private fun checked(value: BigDecimal): BigDecimal {
            if (value.abs() >= LIMIT) throw Overflow()
            return value
        }

        fun expression(): BigDecimal {
            var value = term()
            while (true) {
                value = when (peek()) {
                    Token.Plus -> { pos++; checked(value.add(term(), CONTEXT)) }
                    Token.Minus -> { pos++; checked(value.subtract(term(), CONTEXT)) }
                    else -> return value
                }
            }
        }

        private fun term(): BigDecimal {
            var value = unary()
            while (true) {
                value = when (peek()) {
                    Token.Times -> { pos++; checked(value.multiply(unary(), CONTEXT)) }
                    Token.Divide -> {
                        pos++
                        val divisor = unary()
                        if (divisor.signum() == 0) throw DivideByZero()
                        checked(value.divide(divisor, CONTEXT))
                    }
                    // 隐式乘法：2(3)、(1+1)(2)、(2)3。
                    Token.Open, is Token.Num -> checked(value.multiply(postfix(), CONTEXT))
                    else -> return value
                }
            }
        }

        private fun unary(): BigDecimal = when (peek()) {
            Token.Minus -> { pos++; unary().negate() }
            Token.Plus -> { pos++; unary() }
            else -> postfix()
        }

        private fun postfix(): BigDecimal {
            var value = primary()
            while (peek() == Token.Percent) {
                pos++
                value = value.divide(HUNDRED, CONTEXT)
            }
            return value
        }

        private fun primary(): BigDecimal = when (val token = peek()) {
            is Token.Num -> { pos++; checked(token.value) }
            Token.Open -> {
                pos++
                val inner = expression()
                if (peek() != Token.Close) throw SyntaxError()
                pos++
                inner
            }
            else -> throw SyntaxError()
        }
    }
}
