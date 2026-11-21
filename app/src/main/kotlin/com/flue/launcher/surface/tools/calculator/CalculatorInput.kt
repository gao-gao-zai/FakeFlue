package com.flue.launcher.surface.tools.calculator

/** 计算器按键。 */
sealed interface CalcKey {
    data class Digit(val digit: Char) : CalcKey
    data object Dot : CalcKey
    data class Operator(val symbol: Char) : CalcKey
    data object Open : CalcKey
    data object Close : CalcKey
    data object Percent : CalcKey
    data object Negate : CalcKey
    data object Backspace : CalcKey
    data object Clear : CalcKey
    data object Equals : CalcKey
}

/**
 * 计算器输入状态（纯函数）。[expression] 使用显示字符 `+ − × ÷`。
 * 按“=”后 [result] 是本次结果、[fresh] 为真：接着输入数字开始新算式，输入运算符则接着结果算。
 */
data class CalculatorInput(
    val expression: String = "",
    val result: CalcOutcome? = null,
    val fresh: Boolean = false,
) {
    /** 输入过程中的实时结果；算式不完整或只有一个数时为 null。 */
    val preview: CalcOutcome.Value?
        get() {
            if (fresh || expression.none { it in BINARY || it == '%' || it == '(' }) return null
            return CalculatorEngine.evaluate(expression) as? CalcOutcome.Value
        }

    fun press(key: CalcKey): CalculatorInput = when (key) {
        is CalcKey.Digit -> digit(key.digit)
        CalcKey.Dot -> dot()
        is CalcKey.Operator -> operator(key.symbol)
        CalcKey.Open -> open()
        CalcKey.Close -> close()
        CalcKey.Percent -> percent()
        CalcKey.Negate -> negate()
        CalcKey.Backspace -> if (fresh) CalculatorInput() else copy(expression = expression.dropLast(1), result = null)
        CalcKey.Clear -> CalculatorInput()
        CalcKey.Equals -> equals()
    }

    private fun edit(text: String) = CalculatorInput(expression = text)

    private fun appendable(extra: Int = 1) = expression.length + extra <= MAX_LENGTH

    /** 接着上次结果继续；结果是错误时从空算式开始。 */
    private fun base(): String {
        if (!fresh) return expression
        val value = result as? CalcOutcome.Value ?: return ""
        return CalculatorEngine.continuation(value.value)
    }

    private fun digit(d: Char): CalculatorInput {
        if (!d.isDigit()) return this
        if (fresh) return edit(d.toString())
        if (!appendable(2)) return this
        val last = expression.lastOrNull()
        if (last == ')' || last == '%') return edit("$expression×$d")
        val number = trailingNumber(expression)
        if (number == "0") return edit(expression.dropLast(1) + d)
        return edit(expression + d)
    }

    private fun dot(): CalculatorInput {
        if (fresh) return edit("0.")
        if (!appendable(3)) return this
        val last = expression.lastOrNull()
        return when {
            last == ')' || last == '%' -> edit("$expression×0.")
            last != null && last.isDigit() -> if ('.' in trailingNumber(expression)) this else edit("$expression.")
            last == '.' -> this
            else -> edit("${expression}0.")
        }
    }

    private fun operator(symbol: Char): CalculatorInput {
        val op = when (symbol) {
            '-' -> '−'; '*' -> '×'; '/' -> '÷'; else -> symbol
        }
        if (op !in BINARY) return this
        if (fresh && result !is CalcOutcome.Value && op != '−') return this
        val text = base()
        if (!fresh && !appendable()) return this
        val last = text.lastOrNull()
        return when {
            last == null || last == '(' -> if (op == '−') edit(text + op) else edit(text)
            last in BINARY -> {
                val beforeLast = text.getOrNull(text.length - 2)
                when {
                    // “3×−” 再按运算符：两个都换掉。
                    last == '−' && beforeLast != null && beforeLast in BINARY -> edit(text.dropLast(2) + op)
                    // 开头或括号后的负号只能换成负号。
                    last == '−' && (beforeLast == null || beforeLast == '(') -> edit(text)
                    op == '−' && (last == '×' || last == '÷') -> edit(text + op)
                    else -> edit(text.dropLast(1) + op)
                }
            }
            else -> edit(text + op)
        }
    }

    private fun open(): CalculatorInput {
        if (fresh) return edit("(")
        if (!appendable(2)) return this
        val last = expression.lastOrNull()
        return if (last != null && (last.isDigit() || last == ')' || last == '%' || last == '.')) {
            edit("$expression×(")
        } else {
            edit("$expression(")
        }
    }

    private fun close(): CalculatorInput {
        if (fresh || !appendable()) return this
        val unclosed = expression.count { it == '(' } - expression.count { it == ')' }
        val last = expression.lastOrNull() ?: return this
        if (unclosed <= 0 || !(last.isDigit() || last == ')' || last == '%' || last == '.')) return this
        return edit("$expression)")
    }

    private fun percent(): CalculatorInput {
        val text = base()
        if (!fresh && !appendable()) return this
        val last = text.lastOrNull() ?: return this
        if (!(last.isDigit() || last == ')' || last == '.')) return this
        return edit("$text%")
    }

    /** 正负号：作用于最后一个数（或最后一个括号组）。 */
    private fun negate(): CalculatorInput {
        val text = base()
        if (text.isEmpty()) return edit("−")
        val start = trailingOperandStart(text)
        if (start == null) {
            val last = text.last()
            val beforeLast = text.getOrNull(text.length - 2)
            return when {
                last == '−' && (beforeLast == null || beforeLast in BINARY || beforeLast == '(') -> edit(text.dropLast(1))
                last == '(' || last == '×' || last == '÷' -> edit("$text−")
                else -> if (fresh) edit(text) else this
            }
        }
        val before = text.getOrNull(start - 1)
        val beforeThat = text.getOrNull(start - 2)
        val head = text.substring(0, start)
        val operand = text.substring(start)
        return when {
            before == null || before == '(' || before == '×' || before == '÷' -> edit("$head−$operand")
            before == '−' && (beforeThat == null || beforeThat == '(' || beforeThat in BINARY) ->
                edit(head.dropLast(1) + operand)
            before == '−' -> edit(head.dropLast(1) + "+" + operand)
            before == '+' -> edit(head.dropLast(1) + "−" + operand)
            else -> this
        }
    }

    private fun equals(): CalculatorInput {
        if (fresh || expression.isEmpty()) return this
        return when (val outcome = CalculatorEngine.evaluate(expression)) {
            is CalcOutcome.Value -> CalculatorInput(expression, outcome, fresh = true)
            is CalcOutcome.Error -> copy(result = outcome)
        }
    }

    companion object {
        const val MAX_LENGTH = 64
        val BINARY = charArrayOf('+', '−', '×', '÷').toSet()

        private fun trailingNumber(text: String): String = text.takeLastWhile { it.isDigit() || it == '.' }

        /** 末尾操作数（数字或括号组，可带 %）的起点；末尾不是操作数时为 null。 */
        internal fun trailingOperandStart(text: String): Int? {
            var end = text.length
            while (end > 0 && text[end - 1] == '%') end--
            if (end == 0) return null
            val last = text[end - 1]
            if (last.isDigit() || last == '.') {
                var start = end
                while (start > 0 && (text[start - 1].isDigit() || text[start - 1] == '.')) start--
                return start
            }
            if (last == ')') {
                var depth = 0
                for (i in end - 1 downTo 0) {
                    when (text[i]) {
                        ')' -> depth++
                        '(' -> {
                            depth--
                            if (depth == 0) return i
                        }
                    }
                }
            }
            return null
        }
    }
}
