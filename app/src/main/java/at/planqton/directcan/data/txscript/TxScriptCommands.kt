package at.planqton.directcan.data.txscript

/**
 * Abstract Syntax Tree (AST) nodes for parsed TX Script commands.
 */
sealed class ScriptCommand {
    abstract val line: Int

    /**
     * Variable declaration: var name = expression
     */
    data class VarDeclaration(
        val name: String,
        val value: Expression,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Variable assignment: name = expression
     */
    data class Assignment(
        val name: String,
        val value: Expression,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Send CAN frame: send(id, data, [ext])
     */
    data class Send(
        val canId: Expression,
        val data: Expression,
        val extended: Boolean = false,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Delay execution: delay(ms) or delay(2s)
     */
    data class Delay(
        val duration: Expression,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Repeat loop: repeat(count) { ... }
     */
    data class Repeat(
        val count: Expression,
        val body: List<ScriptCommand>,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Infinite loop: loop { ... }
     */
    data class Loop(
        val body: List<ScriptCommand>,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Conditional: if (condition) { ... } else { ... }
     */
    data class If(
        val condition: Expression,
        val thenBranch: List<ScriptCommand>,
        val elseBranch: List<ScriptCommand>? = null,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Wait for response: wait_for(id, timeout: ms, data: [pattern])
     */
    data class WaitFor(
        val canId: Expression,
        val dataPattern: Expression? = null,
        val timeout: Expression,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Function definition: function name(params) { ... }
     */
    data class FunctionDef(
        val name: String,
        val params: List<String>,
        val body: List<ScriptCommand>,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Function call as statement: functionName(args)
     */
    data class FunctionCallStmt(
        val name: String,
        val args: List<Expression>,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Return statement: return [expression]
     */
    data class Return(
        val value: Expression? = null,
        override val line: Int
    ) : ScriptCommand()

    /**
     * On receive trigger: on_receive(id) { ... }
     */
    data class OnReceive(
        val canId: Expression,
        val body: List<ScriptCommand>,
        override val line: Int
    ) : ScriptCommand()

    /**
     * On interval trigger: on_interval(ms) { ... }
     */
    data class OnInterval(
        val intervalMs: Expression,
        val body: List<ScriptCommand>,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Break statement: break
     */
    data class Break(override val line: Int) : ScriptCommand()

    /**
     * Continue statement: continue
     */
    data class Continue(override val line: Int) : ScriptCommand()

    /**
     * Print statement: print(values...)
     */
    data class Print(
        val values: List<Expression>,
        override val line: Int
    ) : ScriptCommand()

    /**
     * Expression statement (for side effects)
     */
    data class ExpressionStmt(
        val expression: Expression,
        override val line: Int
    ) : ScriptCommand()
}

/**
 * AST nodes for expressions.
 */
sealed class Expression {
    /**
     * Integer literal: 42, 100
     */
    data class IntLiteral(val value: Long) : Expression()

    /**
     * Hex literal: 0x7DF, 0xFF
     */
    data class HexLiteral(val value: Long) : Expression()

    /**
     * Float literal: 3.14, 0.5
     */
    data class FloatLiteral(val value: Double) : Expression()

    /**
     * String literal: "hello"
     */
    data class StringLiteral(val value: String) : Expression()

    /**
     * Boolean literal: true, false
     */
    data class BoolLiteral(val value: Boolean) : Expression()

    /**
     * Byte array literal: [01, 02, 03] or [0x01, 0x02]
     */
    data class ByteArrayLiteral(val bytes: List<Expression>) : Expression()

    /**
     * Variable reference: varName
     */
    data class Variable(val name: String) : Expression()

    /**
     * Array access: array[index]
     */
    data class ArrayAccess(val array: Expression, val index: Expression) : Expression()

    /**
     * Property access: obj.property
     */
    data class PropertyAccess(val obj: Expression, val property: String) : Expression()

    /**
     * Binary operation: left op right
     */
    data class BinaryOp(
        val left: Expression,
        val operator: Operator,
        val right: Expression
    ) : Expression()

    /**
     * Unary operation: op operand
     */
    data class UnaryOp(
        val operator: Operator,
        val operand: Expression
    ) : Expression()

    /**
     * Function call expression: functionName(args)
     */
    data class FunctionCall(
        val name: String,
        val args: List<Expression>
    ) : Expression()

    /**
     * Random number: random(min, max)
     */
    data class Random(val min: Expression, val max: Expression) : Expression()

    /**
     * Random bytes: random_bytes(count)
     */
    data class RandomBytes(val count: Expression) : Expression()

    /**
     * Wildcard for pattern matching: *
     */
    data object Wildcard : Expression()

    /**
     * Parenthesized expression: (expr)
     */
    data class Grouped(val expression: Expression) : Expression()

    /**
     * Ternary/conditional expression: condition ? then : else
     */
    data class Conditional(
        val condition: Expression,
        val thenExpr: Expression,
        val elseExpr: Expression
    ) : Expression()
}

/**
 * Operators for expressions.
 */
enum class Operator(val symbol: String, val precedence: Int) {
    // Arithmetic (precedence 5-6)
    ADD("+", 5),
    SUB("-", 5),
    MUL("*", 6),
    DIV("/", 6),
    MOD("%", 6),

    // Bitwise (precedence 3-4)
    BIT_AND("&", 4),
    BIT_OR("|", 3),
    BIT_XOR("^", 3),
    BIT_NOT("~", 7),
    SHL("<<", 4),
    SHR(">>", 4),

    // Comparison (precedence 2)
    EQ("==", 2),
    NE("!=", 2),
    LT("<", 2),
    LE("<=", 2),
    GT(">", 2),
    GE(">=", 2),

    // Logical (precedence 1)
    AND("&&", 1),
    OR("||", 0),
    NOT("!", 7),

    // Unary
    NEGATE("-", 7);

    companion object {
        fun fromSymbol(symbol: String): Operator? = entries.find { it.symbol == symbol }
    }
}

/**
 * Result of parsing a script.
 */
data class ParseResult(
    val commands: List<ScriptCommand>,
    val functions: Map<String, ScriptCommand.FunctionDef>,
    val triggers: List<ScriptCommand>,  // on_receive, on_interval
    val errors: List<ScriptError>,
    val totalLines: Int
) {
    val isValid: Boolean get() = errors.isEmpty()

    companion object {
        fun error(errors: List<ScriptError>): ParseResult {
            return ParseResult(
                commands = emptyList(),
                functions = emptyMap(),
                triggers = emptyList(),
                errors = errors,
                totalLines = 0
            )
        }
    }
}
