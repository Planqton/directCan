package at.planqton.directcan.data.txscript.parser

import at.planqton.directcan.data.txscript.ErrorType
import at.planqton.directcan.data.txscript.ScriptError

/**
 * Token types for the TX Script language.
 */
enum class TokenType {
    // Literals
    NUMBER,         // 42, 100
    HEX_NUMBER,     // 0x7DF, 0xFF
    FLOAT_NUMBER,   // 3.14
    STRING,         // "hello"

    // Identifiers & Keywords
    IDENTIFIER,     // variable/function names
    VAR,            // var
    SEND,           // send
    DELAY,          // delay
    REPEAT,         // repeat
    LOOP,           // loop
    IF,             // if
    ELSE,           // else
    WAIT_FOR,       // wait_for
    RANDOM,         // random
    RANDOM_BYTES,   // random_bytes
    FUNCTION,       // function
    RETURN,         // return
    ON_RECEIVE,     // on_receive
    ON_INTERVAL,    // on_interval
    BREAK,          // break
    CONTINUE,       // continue
    PRINT,          // print
    EXT,            // ext
    TIMEOUT,        // timeout
    DATA,           // data
    TRUE,           // true
    FALSE,          // false

    // Operators
    PLUS,           // +
    MINUS,          // -
    STAR,           // *
    SLASH,          // /
    PERCENT,        // %
    AMPERSAND,      // &
    PIPE,           // |
    CARET,          // ^
    TILDE,          // ~
    SHL,            // <<
    SHR,            // >>
    EQ,             // ==
    NE,             // !=
    LT,             // <
    LE,             // <=
    GT,             // >
    GE,             // >=
    AND,            // &&
    OR,             // ||
    NOT,            // !
    ASSIGN,         // =
    QUESTION,       // ?

    // Delimiters
    LPAREN,         // (
    RPAREN,         // )
    LBRACE,         // {
    RBRACE,         // }
    LBRACKET,       // [
    RBRACKET,       // ]
    COMMA,          // ,
    COLON,          // :
    DOT,            // .
    SEMICOLON,      // ;

    // Special
    EOF,
    NEWLINE,
    ERROR
}

/**
 * Represents a single token from the source code.
 */
data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val column: Int
) {
    override fun toString(): String = "Token($type, '$value', $line:$column)"
}

/**
 * Lexer (Tokenizer) for the TX Script language.
 */
class TxScriptLexer(private val source: String) {

    private var pos = 0
    private var line = 1
    private var column = 1
    private var tokenStart = 0
    private var tokenStartColumn = 1

    private val tokens = mutableListOf<Token>()
    private val errors = mutableListOf<ScriptError>()

    companion object {
        private val KEYWORDS = mapOf(
            "var" to TokenType.VAR,
            "send" to TokenType.SEND,
            "delay" to TokenType.DELAY,
            "repeat" to TokenType.REPEAT,
            "loop" to TokenType.LOOP,
            "if" to TokenType.IF,
            "else" to TokenType.ELSE,
            "wait_for" to TokenType.WAIT_FOR,
            "random" to TokenType.RANDOM,
            "random_bytes" to TokenType.RANDOM_BYTES,
            "function" to TokenType.FUNCTION,
            "return" to TokenType.RETURN,
            "on_receive" to TokenType.ON_RECEIVE,
            "on_interval" to TokenType.ON_INTERVAL,
            "break" to TokenType.BREAK,
            "continue" to TokenType.CONTINUE,
            "print" to TokenType.PRINT,
            "ext" to TokenType.EXT,
            "timeout" to TokenType.TIMEOUT,
            "data" to TokenType.DATA,
            "true" to TokenType.TRUE,
            "false" to TokenType.FALSE
        )
    }

    /**
     * Tokenize the source code.
     * @return Pair of token list and any errors encountered.
     */
    fun tokenize(): Pair<List<Token>, List<ScriptError>> {
        tokens.clear()
        errors.clear()

        while (!isAtEnd()) {
            tokenStart = pos
            tokenStartColumn = column
            scanToken()
        }

        tokens.add(Token(TokenType.EOF, "", line, column))
        return Pair(tokens.toList(), errors.toList())
    }

    private fun scanToken() {
        val c = advance()

        when (c) {
            // Single-character tokens
            '(' -> addToken(TokenType.LPAREN)
            ')' -> addToken(TokenType.RPAREN)
            '{' -> addToken(TokenType.LBRACE)
            '}' -> addToken(TokenType.RBRACE)
            '[' -> addToken(TokenType.LBRACKET)
            ']' -> addToken(TokenType.RBRACKET)
            ',' -> addToken(TokenType.COMMA)
            ':' -> addToken(TokenType.COLON)
            '.' -> addToken(TokenType.DOT)
            ';' -> addToken(TokenType.SEMICOLON)
            '+' -> addToken(TokenType.PLUS)
            '-' -> addToken(TokenType.MINUS)
            '*' -> addToken(TokenType.STAR)
            '%' -> addToken(TokenType.PERCENT)
            '^' -> addToken(TokenType.CARET)
            '~' -> addToken(TokenType.TILDE)
            '?' -> addToken(TokenType.QUESTION)

            // Two-character tokens
            '/' -> {
                when {
                    match('/') -> skipLineComment()
                    match('*') -> skipBlockComment()
                    else -> addToken(TokenType.SLASH)
                }
            }
            '=' -> addToken(if (match('=')) TokenType.EQ else TokenType.ASSIGN)
            '!' -> addToken(if (match('=')) TokenType.NE else TokenType.NOT)
            '<' -> {
                when {
                    match('<') -> addToken(TokenType.SHL)
                    match('=') -> addToken(TokenType.LE)
                    else -> addToken(TokenType.LT)
                }
            }
            '>' -> {
                when {
                    match('>') -> addToken(TokenType.SHR)
                    match('=') -> addToken(TokenType.GE)
                    else -> addToken(TokenType.GT)
                }
            }
            '&' -> addToken(if (match('&')) TokenType.AND else TokenType.AMPERSAND)
            '|' -> addToken(if (match('|')) TokenType.OR else TokenType.PIPE)

            // Whitespace
            ' ', '\r', '\t' -> { /* Ignore */ }
            '\n' -> {
                addToken(TokenType.NEWLINE)
                line++
                column = 1
            }

            // String literals
            '"' -> readString()

            else -> {
                when {
                    c.isDigit() -> readNumber()
                    c.isLetter() || c == '_' -> readIdentifier()
                    else -> error("Unexpected character: '$c'")
                }
            }
        }
    }

    private fun readNumber() {
        // Check for hex number
        if (peek(-1) == '0' && (peek() == 'x' || peek() == 'X')) {
            advance() // consume 'x'
            while (peek().isHexDigit()) advance()
            addToken(TokenType.HEX_NUMBER)
            return
        }

        // Regular number
        while (peek().isDigit()) advance()

        // Check for float
        if (peek() == '.' && peek(1).isDigit()) {
            advance() // consume '.'
            while (peek().isDigit()) advance()
            addToken(TokenType.FLOAT_NUMBER)
            return
        }

        // Check for time suffix (s for seconds)
        if (peek() == 's' && !peek(1).isLetterOrDigit()) {
            advance() // consume 's'
        }

        addToken(TokenType.NUMBER)
    }

    private fun readIdentifier() {
        while (peek().isLetterOrDigit() || peek() == '_') advance()

        val text = source.substring(tokenStart, pos)
        val type = KEYWORDS[text] ?: TokenType.IDENTIFIER
        addToken(type)
    }

    private fun readString() {
        val startLine = line
        val startColumn = tokenStartColumn

        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') {
                line++
                column = 0
            }
            if (peek() == '\\' && peek(1) == '"') {
                advance() // skip escape
            }
            advance()
        }

        if (isAtEnd()) {
            errors.add(ScriptError(
                line = startLine,
                column = startColumn,
                message = "Unterminated string",
                type = ErrorType.PARSE_ERROR
            ))
            return
        }

        advance() // closing "

        // Extract string value (without quotes, handle escapes)
        val rawValue = source.substring(tokenStart + 1, pos - 1)
        val value = rawValue
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        addToken(TokenType.STRING, value)
    }

    private fun skipLineComment() {
        while (peek() != '\n' && !isAtEnd()) advance()
    }

    private fun skipBlockComment() {
        var depth = 1
        while (depth > 0 && !isAtEnd()) {
            if (peek() == '/' && peek(1) == '*') {
                advance()
                advance()
                depth++
            } else if (peek() == '*' && peek(1) == '/') {
                advance()
                advance()
                depth--
            } else {
                if (peek() == '\n') {
                    line++
                    column = 0
                }
                advance()
            }
        }

        if (depth > 0) {
            errors.add(ScriptError(
                line = line,
                column = column,
                message = "Unterminated block comment",
                type = ErrorType.PARSE_ERROR
            ))
        }
    }

    // =============== HELPERS ===============

    private fun isAtEnd(): Boolean = pos >= source.length

    private fun peek(offset: Int = 0): Char {
        val idx = pos + offset
        return if (idx >= source.length || idx < 0) '\u0000' else source[idx]
    }

    private fun advance(): Char {
        val c = source[pos]
        pos++
        column++
        return c
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd()) return false
        if (source[pos] != expected) return false
        pos++
        column++
        return true
    }

    private fun addToken(type: TokenType, value: String = source.substring(tokenStart, pos)) {
        tokens.add(Token(type, value, line, tokenStartColumn))
    }

    private fun error(message: String) {
        errors.add(ScriptError(
            line = line,
            column = tokenStartColumn,
            message = message,
            type = ErrorType.PARSE_ERROR
        ))
        addToken(TokenType.ERROR)
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
