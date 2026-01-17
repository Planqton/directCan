package at.planqton.directcan.data.txscript.parser

import at.planqton.directcan.data.txscript.*

/**
 * Parser for the TX Script language.
 * Converts a list of tokens into an Abstract Syntax Tree (AST).
 */
class TxScriptParser(private val tokens: List<Token>) {

    private var current = 0
    private val errors = mutableListOf<ScriptError>()
    private val functions = mutableMapOf<String, ScriptCommand.FunctionDef>()
    private val triggers = mutableListOf<ScriptCommand>()

    /**
     * Parse the token stream into a ParseResult.
     */
    fun parse(): ParseResult {
        val commands = mutableListOf<ScriptCommand>()

        while (!isAtEnd()) {
            skipNewlines()
            if (isAtEnd()) break

            try {
                val cmd = parseStatement()
                if (cmd != null) {
                    // Separate triggers and functions
                    when (cmd) {
                        is ScriptCommand.FunctionDef -> functions[cmd.name] = cmd
                        is ScriptCommand.OnReceive, is ScriptCommand.OnInterval -> triggers.add(cmd)
                        else -> commands.add(cmd)
                    }
                }
            } catch (e: ParseException) {
                errors.add(e.toError())
                synchronize()
            }
        }

        return ParseResult(
            commands = commands,
            functions = functions.toMap(),
            triggers = triggers.toList(),
            errors = errors.toList(),
            totalLines = tokens.lastOrNull()?.line ?: 0
        )
    }

    // =============== STATEMENTS ===============

    private fun parseStatement(): ScriptCommand? {
        skipNewlines()
        if (isAtEnd()) return null

        return when (peek().type) {
            TokenType.VAR -> parseVarDeclaration()
            TokenType.SEND -> parseSend()
            TokenType.DELAY -> parseDelay()
            TokenType.REPEAT -> parseRepeat()
            TokenType.LOOP -> parseLoop()
            TokenType.IF -> parseIf()
            TokenType.WAIT_FOR -> parseWaitFor()
            TokenType.FUNCTION -> parseFunction()
            TokenType.RETURN -> parseReturn()
            TokenType.ON_RECEIVE -> parseOnReceive()
            TokenType.ON_INTERVAL -> parseOnInterval()
            TokenType.BREAK -> parseBreak()
            TokenType.CONTINUE -> parseContinue()
            TokenType.PRINT -> parsePrint()
            TokenType.IDENTIFIER -> parseIdentifierStatement()
            TokenType.NEWLINE, TokenType.SEMICOLON -> {
                advance()
                null
            }
            else -> {
                val token = peek()
                throw ParseException("Unexpected token: ${token.value}", token)
            }
        }
    }

    private fun parseVarDeclaration(): ScriptCommand.VarDeclaration {
        val varToken = consume(TokenType.VAR, "Expected 'var'")
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").value
        consume(TokenType.ASSIGN, "Expected '=' after variable name")
        val value = parseExpression()
        consumeStatementEnd()
        return ScriptCommand.VarDeclaration(name, value, varToken.line)
    }

    private fun parseSend(): ScriptCommand.Send {
        val sendToken = consume(TokenType.SEND, "Expected 'send'")
        consume(TokenType.LPAREN, "Expected '(' after 'send'")

        val canId = parseExpression()
        consume(TokenType.COMMA, "Expected ',' after CAN ID")
        val data = parseExpression()

        var extended = false
        if (match(TokenType.COMMA)) {
            if (check(TokenType.EXT)) {
                advance()
                extended = true
            }
        }

        consume(TokenType.RPAREN, "Expected ')' after send arguments")
        consumeStatementEnd()

        return ScriptCommand.Send(canId, data, extended, sendToken.line)
    }

    private fun parseDelay(): ScriptCommand.Delay {
        val delayToken = consume(TokenType.DELAY, "Expected 'delay'")
        consume(TokenType.LPAREN, "Expected '(' after 'delay'")
        val duration = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after delay duration")
        consumeStatementEnd()
        return ScriptCommand.Delay(duration, delayToken.line)
    }

    private fun parseRepeat(): ScriptCommand.Repeat {
        val repeatToken = consume(TokenType.REPEAT, "Expected 'repeat'")
        consume(TokenType.LPAREN, "Expected '(' after 'repeat'")
        val count = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after repeat count")
        val body = parseBlock()
        return ScriptCommand.Repeat(count, body, repeatToken.line)
    }

    private fun parseLoop(): ScriptCommand.Loop {
        val loopToken = consume(TokenType.LOOP, "Expected 'loop'")
        val body = parseBlock()
        return ScriptCommand.Loop(body, loopToken.line)
    }

    private fun parseIf(): ScriptCommand.If {
        val ifToken = consume(TokenType.IF, "Expected 'if'")
        consume(TokenType.LPAREN, "Expected '(' after 'if'")
        val condition = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after condition")
        val thenBranch = parseBlock()

        var elseBranch: List<ScriptCommand>? = null
        skipNewlines()
        if (check(TokenType.ELSE)) {
            advance()
            skipNewlines()
            elseBranch = if (check(TokenType.IF)) {
                listOf(parseIf())
            } else {
                parseBlock()
            }
        }

        return ScriptCommand.If(condition, thenBranch, elseBranch, ifToken.line)
    }

    private fun parseWaitFor(): ScriptCommand.WaitFor {
        val waitToken = consume(TokenType.WAIT_FOR, "Expected 'wait_for'")
        consume(TokenType.LPAREN, "Expected '(' after 'wait_for'")

        val canId = parseExpression()
        var dataPattern: Expression? = null
        var timeout: Expression = Expression.IntLiteral(1000) // Default 1s

        while (match(TokenType.COMMA)) {
            val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name").value
            consume(TokenType.COLON, "Expected ':' after parameter name")

            when (paramName) {
                "timeout" -> timeout = parseExpression()
                "data" -> dataPattern = parseExpression()
                else -> throw ParseException("Unknown parameter: $paramName", previous())
            }
        }

        consume(TokenType.RPAREN, "Expected ')' after wait_for arguments")
        consumeStatementEnd()

        return ScriptCommand.WaitFor(canId, dataPattern, timeout, waitToken.line)
    }

    private fun parseFunction(): ScriptCommand.FunctionDef {
        val funcToken = consume(TokenType.FUNCTION, "Expected 'function'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name").value
        consume(TokenType.LPAREN, "Expected '(' after function name")

        val params = mutableListOf<String>()
        if (!check(TokenType.RPAREN)) {
            do {
                params.add(consume(TokenType.IDENTIFIER, "Expected parameter name").value)
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected ')' after parameters")
        val body = parseBlock()

        return ScriptCommand.FunctionDef(name, params, body, funcToken.line)
    }

    private fun parseReturn(): ScriptCommand.Return {
        val returnToken = consume(TokenType.RETURN, "Expected 'return'")

        val value = if (!check(TokenType.NEWLINE) && !check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd()) {
            parseExpression()
        } else {
            null
        }

        consumeStatementEnd()
        return ScriptCommand.Return(value, returnToken.line)
    }

    private fun parseOnReceive(): ScriptCommand.OnReceive {
        val onToken = consume(TokenType.ON_RECEIVE, "Expected 'on_receive'")
        consume(TokenType.LPAREN, "Expected '(' after 'on_receive'")
        val canId = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after CAN ID")
        val body = parseBlock()
        return ScriptCommand.OnReceive(canId, body, onToken.line)
    }

    private fun parseOnInterval(): ScriptCommand.OnInterval {
        val onToken = consume(TokenType.ON_INTERVAL, "Expected 'on_interval'")
        consume(TokenType.LPAREN, "Expected '(' after 'on_interval'")
        val interval = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after interval")
        val body = parseBlock()
        return ScriptCommand.OnInterval(interval, body, onToken.line)
    }

    private fun parseBreak(): ScriptCommand.Break {
        val token = consume(TokenType.BREAK, "Expected 'break'")
        consumeStatementEnd()
        return ScriptCommand.Break(token.line)
    }

    private fun parseContinue(): ScriptCommand.Continue {
        val token = consume(TokenType.CONTINUE, "Expected 'continue'")
        consumeStatementEnd()
        return ScriptCommand.Continue(token.line)
    }

    private fun parsePrint(): ScriptCommand.Print {
        val printToken = consume(TokenType.PRINT, "Expected 'print'")
        consume(TokenType.LPAREN, "Expected '(' after 'print'")

        val values = mutableListOf<Expression>()
        if (!check(TokenType.RPAREN)) {
            do {
                values.add(parseExpression())
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected ')' after print arguments")
        consumeStatementEnd()

        return ScriptCommand.Print(values, printToken.line)
    }

    private fun parseIdentifierStatement(): ScriptCommand {
        val identifier = consume(TokenType.IDENTIFIER, "Expected identifier")
        val line = identifier.line

        return when {
            // Assignment: name = value
            check(TokenType.ASSIGN) -> {
                advance()
                val value = parseExpression()
                consumeStatementEnd()
                ScriptCommand.Assignment(identifier.value, value, line)
            }
            // Function call: name(args)
            check(TokenType.LPAREN) -> {
                advance()
                val args = mutableListOf<Expression>()
                if (!check(TokenType.RPAREN)) {
                    do {
                        args.add(parseExpression())
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.RPAREN, "Expected ')' after arguments")
                consumeStatementEnd()
                ScriptCommand.FunctionCallStmt(identifier.value, args, line)
            }
            else -> {
                throw ParseException("Expected '=' or '(' after identifier", identifier)
            }
        }
    }

    private fun parseBlock(): List<ScriptCommand> {
        skipNewlines()
        consume(TokenType.LBRACE, "Expected '{'")
        skipNewlines()

        val statements = mutableListOf<ScriptCommand>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            val stmt = parseStatement()
            if (stmt != null) statements.add(stmt)
            skipNewlines()
        }

        consume(TokenType.RBRACE, "Expected '}'")
        return statements
    }

    // =============== EXPRESSIONS ===============

    private fun parseExpression(): Expression {
        return parseOr()
    }

    private fun parseOr(): Expression {
        var expr = parseAnd()
        while (match(TokenType.OR)) {
            val right = parseAnd()
            expr = Expression.BinaryOp(expr, Operator.OR, right)
        }
        return expr
    }

    private fun parseAnd(): Expression {
        var expr = parseEquality()
        while (match(TokenType.AND)) {
            val right = parseEquality()
            expr = Expression.BinaryOp(expr, Operator.AND, right)
        }
        return expr
    }

    private fun parseEquality(): Expression {
        var expr = parseComparison()
        while (match(TokenType.EQ, TokenType.NE)) {
            val op = if (previous().type == TokenType.EQ) Operator.EQ else Operator.NE
            val right = parseComparison()
            expr = Expression.BinaryOp(expr, op, right)
        }
        return expr
    }

    private fun parseComparison(): Expression {
        var expr = parseBitOr()
        while (match(TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE)) {
            val op = when (previous().type) {
                TokenType.LT -> Operator.LT
                TokenType.LE -> Operator.LE
                TokenType.GT -> Operator.GT
                TokenType.GE -> Operator.GE
                else -> throw ParseException("Unexpected comparison operator", previous())
            }
            val right = parseBitOr()
            expr = Expression.BinaryOp(expr, op, right)
        }
        return expr
    }

    private fun parseBitOr(): Expression {
        var expr = parseBitXor()
        while (match(TokenType.PIPE) && !check(TokenType.PIPE)) {
            val right = parseBitXor()
            expr = Expression.BinaryOp(expr, Operator.BIT_OR, right)
        }
        return expr
    }

    private fun parseBitXor(): Expression {
        var expr = parseBitAnd()
        while (match(TokenType.CARET)) {
            val right = parseBitAnd()
            expr = Expression.BinaryOp(expr, Operator.BIT_XOR, right)
        }
        return expr
    }

    private fun parseBitAnd(): Expression {
        var expr = parseShift()
        while (match(TokenType.AMPERSAND) && !check(TokenType.AMPERSAND)) {
            val right = parseShift()
            expr = Expression.BinaryOp(expr, Operator.BIT_AND, right)
        }
        return expr
    }

    private fun parseShift(): Expression {
        var expr = parseTerm()
        while (match(TokenType.SHL, TokenType.SHR)) {
            val op = if (previous().type == TokenType.SHL) Operator.SHL else Operator.SHR
            val right = parseTerm()
            expr = Expression.BinaryOp(expr, op, right)
        }
        return expr
    }

    private fun parseTerm(): Expression {
        var expr = parseFactor()
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            val op = if (previous().type == TokenType.PLUS) Operator.ADD else Operator.SUB
            val right = parseFactor()
            expr = Expression.BinaryOp(expr, op, right)
        }
        return expr
    }

    private fun parseFactor(): Expression {
        var expr = parseUnary()
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            val op = when (previous().type) {
                TokenType.STAR -> Operator.MUL
                TokenType.SLASH -> Operator.DIV
                TokenType.PERCENT -> Operator.MOD
                else -> throw ParseException("Unexpected factor operator", previous())
            }
            val right = parseUnary()
            expr = Expression.BinaryOp(expr, op, right)
        }
        return expr
    }

    private fun parseUnary(): Expression {
        if (match(TokenType.NOT)) {
            val operand = parseUnary()
            return Expression.UnaryOp(Operator.NOT, operand)
        }
        if (match(TokenType.MINUS)) {
            val operand = parseUnary()
            return Expression.UnaryOp(Operator.NEGATE, operand)
        }
        if (match(TokenType.TILDE)) {
            val operand = parseUnary()
            return Expression.UnaryOp(Operator.BIT_NOT, operand)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expression {
        var expr = parsePrimary()

        while (true) {
            expr = when {
                match(TokenType.DOT) -> {
                    val property = consume(TokenType.IDENTIFIER, "Expected property name").value
                    Expression.PropertyAccess(expr, property)
                }
                match(TokenType.LBRACKET) -> {
                    val index = parseExpression()
                    consume(TokenType.RBRACKET, "Expected ']' after index")
                    Expression.ArrayAccess(expr, index)
                }
                else -> break
            }
        }

        return expr
    }

    private fun parsePrimary(): Expression {
        return when {
            match(TokenType.NUMBER) -> {
                val value = previous().value
                // Check for time suffix
                if (value.endsWith("s")) {
                    val seconds = value.dropLast(1).toLongOrNull() ?: 0
                    Expression.IntLiteral(seconds * 1000)
                } else {
                    Expression.IntLiteral(value.toLongOrNull() ?: 0)
                }
            }
            match(TokenType.HEX_NUMBER) -> {
                val hex = previous().value.removePrefix("0x").removePrefix("0X")
                Expression.HexLiteral(hex.toLongOrNull(16) ?: 0)
            }
            match(TokenType.FLOAT_NUMBER) -> {
                Expression.FloatLiteral(previous().value.toDoubleOrNull() ?: 0.0)
            }
            match(TokenType.STRING) -> {
                Expression.StringLiteral(previous().value)
            }
            match(TokenType.TRUE) -> Expression.BoolLiteral(true)
            match(TokenType.FALSE) -> Expression.BoolLiteral(false)
            match(TokenType.STAR) -> Expression.Wildcard
            match(TokenType.LBRACKET) -> parseByteArray()
            match(TokenType.LPAREN) -> {
                val expr = parseExpression()
                consume(TokenType.RPAREN, "Expected ')' after expression")
                Expression.Grouped(expr)
            }
            match(TokenType.RANDOM) -> {
                consume(TokenType.LPAREN, "Expected '(' after 'random'")
                val min = parseExpression()
                consume(TokenType.COMMA, "Expected ',' between min and max")
                val max = parseExpression()
                consume(TokenType.RPAREN, "Expected ')' after random arguments")
                Expression.Random(min, max)
            }
            match(TokenType.RANDOM_BYTES) -> {
                consume(TokenType.LPAREN, "Expected '(' after 'random_bytes'")
                val count = parseExpression()
                consume(TokenType.RPAREN, "Expected ')' after random_bytes count")
                Expression.RandomBytes(count)
            }
            match(TokenType.IDENTIFIER) -> {
                val name = previous().value

                // Check for function call
                if (check(TokenType.LPAREN)) {
                    advance()
                    val args = mutableListOf<Expression>()
                    if (!check(TokenType.RPAREN)) {
                        do {
                            args.add(parseExpression())
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.RPAREN, "Expected ')' after arguments")
                    Expression.FunctionCall(name, args)
                } else {
                    Expression.Variable(name)
                }
            }
            else -> {
                throw ParseException("Expected expression", peek())
            }
        }
    }

    private fun parseByteArray(): Expression.ByteArrayLiteral {
        val bytes = mutableListOf<Expression>()

        if (!check(TokenType.RBRACKET)) {
            do {
                bytes.add(parseExpression())
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RBRACKET, "Expected ']' after byte array")
        return Expression.ByteArrayLiteral(bytes)
    }

    // =============== HELPERS ===============

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun peek(): Token = tokens.getOrElse(current) { tokens.last() }

    private fun previous(): Token = tokens.getOrElse(current - 1) { tokens.first() }

    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun check(type: TokenType): Boolean = peek().type == type

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw ParseException(message, peek())
    }

    private fun consumeStatementEnd() {
        // Allow newline, semicolon, or end of block/file
        if (match(TokenType.NEWLINE, TokenType.SEMICOLON)) return
        if (check(TokenType.RBRACE) || isAtEnd()) return
        // Ignore if followed immediately by another statement keyword
    }

    private fun skipNewlines() {
        while (match(TokenType.NEWLINE, TokenType.SEMICOLON)) { /* skip */ }
    }

    private fun synchronize() {
        advance()
        while (!isAtEnd()) {
            if (previous().type == TokenType.NEWLINE) return
            if (previous().type == TokenType.SEMICOLON) return

            when (peek().type) {
                TokenType.VAR, TokenType.SEND, TokenType.DELAY, TokenType.REPEAT,
                TokenType.LOOP, TokenType.IF, TokenType.WAIT_FOR, TokenType.FUNCTION,
                TokenType.RETURN, TokenType.ON_RECEIVE, TokenType.ON_INTERVAL,
                TokenType.BREAK, TokenType.CONTINUE, TokenType.PRINT -> return
                else -> advance()
            }
        }
    }
}

/**
 * Exception thrown during parsing.
 */
class ParseException(
    message: String,
    val token: Token
) : Exception(message) {
    fun toError(): ScriptError = ScriptError(
        line = token.line,
        column = token.column,
        message = message ?: "Parse error",
        type = ErrorType.PARSE_ERROR
    )
}

/**
 * Utility function to parse a script string.
 */
fun parseScript(source: String): ParseResult {
    val lexer = TxScriptLexer(source)
    val (tokens, lexerErrors) = lexer.tokenize()

    if (lexerErrors.isNotEmpty()) {
        return ParseResult.error(lexerErrors)
    }

    val parser = TxScriptParser(tokens)
    return parser.parse()
}
