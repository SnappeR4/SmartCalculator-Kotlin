package calculator

import java.math.BigInteger
import java.util.Scanner

/**
 * Custom Exceptions for crash-proof routing
 */
class InvalidIdentifierException : Exception()
class InvalidAssignmentException : Exception()
class UnknownVariableException : Exception()
class InvalidExpressionException : Exception()

/**
 * Core Data Structures
 */
sealed class Token {
    data class Number(val value: BigInteger) : Token()
    data class Variable(val name: String) : Token()
    object Add : Token()
    object Subtract : Token()
    object Multiply : Token()
    object Divide : Token()
    object Power : Token()
    object LeftParen : Token()
    object RightParen : Token()
}

fun Token.precedence(): Int = when (this) {
    is Token.Power -> 3
    is Token.Multiply, is Token.Divide -> 2
    is Token.Add, is Token.Subtract -> 1
    else -> 0
}

/**
 * State Management Layer (Repository)
 */
class Memory {
    private val vars = mutableMapOf<String, BigInteger>()
    fun set(name: String, value: BigInteger) { vars[name] = value }
    fun get(name: String): BigInteger = vars[name] ?: throw UnknownVariableException()
}

/**
 * Parsing Layer: Extracts tokens, handles unary operators, and validates structure.
 */
class ExpressionParser {
    fun parse(input: String): List<Token> {
        if (input.matches(Regex(".*[*/^]{2,}.*"))) throw InvalidExpressionException()
        if (input.contains(Regex("[a-zA-Z]\\d|\\d[a-zA-Z]"))) throw InvalidIdentifierException()

        var sanitized = input
        while (sanitized.contains(Regex("[+-]\\s+[+-]"))) {
            sanitized = sanitized.replace(Regex("([+-])\\s+([+-])"), "$1$2")
        }

        // \\d+ naturally captures arbitrarily large numbers for BigInteger
        val tokenRegex = Regex("([a-zA-Z]+|\\d+|[+-]+|[*/^()])")
        val matches = tokenRegex.findAll(sanitized).map { it.value }.toList()

        val cleanInput = sanitized.replace(Regex("\\s+"), "")
        if (cleanInput != matches.joinToString("")) throw InvalidExpressionException()

        val tokens = mutableListOf<Token>()
        var previousWasOperatorOrParen = true

        for (raw in matches) {
            when {
                raw.matches(Regex("\\d+")) -> {
                    tokens.add(Token.Number(raw.toBigInteger()))
                    previousWasOperatorOrParen = false
                }
                raw.matches(Regex("[a-zA-Z]+")) -> {
                    tokens.add(Token.Variable(raw))
                    previousWasOperatorOrParen = false
                }
                raw == "(" -> {
                    tokens.add(Token.LeftParen)
                    previousWasOperatorOrParen = true
                }
                raw == ")" -> {
                    tokens.add(Token.RightParen)
                    previousWasOperatorOrParen = false
                }
                raw.matches(Regex("[+-]+")) -> {
                    val minusCount = raw.count { it == '-' }
                    val op = if (minusCount % 2 == 1) Token.Subtract else Token.Add

                    if (previousWasOperatorOrParen && (tokens.isEmpty() || tokens.last() == Token.LeftParen)) {
                        tokens.add(Token.Number(BigInteger.ZERO))
                    }
                    tokens.add(op)
                    previousWasOperatorOrParen = true
                }
                raw == "*" -> { tokens.add(Token.Multiply); previousWasOperatorOrParen = true }
                raw == "/" -> { tokens.add(Token.Divide); previousWasOperatorOrParen = true }
                raw == "^" -> { tokens.add(Token.Power); previousWasOperatorOrParen = true }
                else -> throw InvalidExpressionException()
            }
        }

        var expectOperand = true
        var parenCount = 0
        for (token in tokens) {
            when (token) {
                is Token.Number, is Token.Variable -> {
                    if (!expectOperand) throw InvalidExpressionException()
                    expectOperand = false
                }
                is Token.LeftParen -> {
                    if (!expectOperand) throw InvalidExpressionException()
                    parenCount++
                }
                is Token.RightParen -> {
                    if (expectOperand) throw InvalidExpressionException()
                    parenCount--
                    if (parenCount < 0) throw InvalidExpressionException()
                }
                is Token.Add, is Token.Subtract, is Token.Multiply, is Token.Divide, is Token.Power -> {
                    if (expectOperand) throw InvalidExpressionException()
                    expectOperand = true
                }
            }
        }
        if (expectOperand && tokens.isNotEmpty()) throw InvalidExpressionException()
        if (parenCount != 0) throw InvalidExpressionException()

        return tokens
    }
}

/**
 * Shunting-Yard Algorithm Layer: Converts Infix to Postfix
 */
class InfixToPostfixConverter {
    fun convert(infix: List<Token>): List<Token> {
        val postfix = mutableListOf<Token>()
        val stack = ArrayDeque<Token>()

        for (token in infix) {
            when (token) {
                is Token.Number, is Token.Variable -> postfix.add(token)
                is Token.LeftParen -> stack.addLast(token)
                is Token.RightParen -> {
                    var foundLeft = false
                    while (stack.isNotEmpty()) {
                        val top = stack.removeLast()
                        if (top is Token.LeftParen) {
                            foundLeft = true
                            break
                        }
                        postfix.add(top)
                    }
                    if (!foundLeft) throw InvalidExpressionException()
                }
                is Token.Add, is Token.Subtract, is Token.Multiply, is Token.Divide, is Token.Power -> {
                    while (stack.isNotEmpty() && stack.last() != Token.LeftParen) {
                        val top = stack.last()
                        if (token is Token.Power && top is Token.Power) break

                        if (top.precedence() >= token.precedence()) {
                            postfix.add(stack.removeLast())
                        } else {
                            break
                        }
                    }
                    stack.addLast(token)
                }
            }
        }

        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            if (top is Token.LeftParen || top is Token.RightParen) throw InvalidExpressionException()
            postfix.add(top)
        }

        return postfix
    }
}

/**
 * Domain Layer: Stack Evaluator (Natively handles BigInteger math)
 */
class Calculator(private val memory: Memory) {
    fun evaluate(postfix: List<Token>): BigInteger {
        if (postfix.isEmpty()) return BigInteger.ZERO

        val stack = ArrayDeque<BigInteger>()

        for (token in postfix) {
            when (token) {
                is Token.Number -> stack.addLast(token.value)
                is Token.Variable -> stack.addLast(memory.get(token.name))
                is Token.Add, is Token.Subtract, is Token.Multiply, is Token.Divide, is Token.Power -> {
                    if (stack.size < 2) throw InvalidExpressionException()
                    val b = stack.removeLast()
                    val a = stack.removeLast()

                    val result = when (token) {
                        is Token.Add -> a + b
                        is Token.Subtract -> a - b
                        is Token.Multiply -> a * b
                        is Token.Divide -> {
                            if (b == BigInteger.ZERO) throw ArithmeticException("Divide by zero")
                            a / b
                        }
                        is Token.Power -> a.pow(b.toInt())
                        else -> throw InvalidExpressionException()
                    }
                    stack.addLast(result)
                }
                else -> throw InvalidExpressionException()
            }
        }
        if (stack.size != 1) throw InvalidExpressionException()
        return stack.removeLast()
    }
}

/**
 * Infrastructure Layer: I/O Isolation
 */
class IOHandler {
    private val scanner = Scanner(System.`in`)
    fun readLine(): String = if (scanner.hasNextLine()) scanner.nextLine().trim() else ""
    fun printMessage(message: String) = println(message)
}

/**
 * Application Layer (Controller)
 */
class CalculatorApp(
    private val ioHandler: IOHandler,
    private val parser: ExpressionParser,
    private val converter: InfixToPostfixConverter,
    private val calculator: Calculator,
    private val memory: Memory
) {
    fun run() {
        while (true) {
            val input = ioHandler.readLine()
            if (input.isEmpty()) continue

            if (input.startsWith("/")) {
                when (input) {
                    "/exit" -> {
                        ioHandler.printMessage("Bye!")
                        return
                    }
                    "/help" -> {
                        ioHandler.printMessage("Calculates complex expressions utilizing PEMDAS order of operations. Supports arbitrarily large numbers, +, -, *, /, ^ (power), parentheses (...), and persistent variable assignment.")
                    }
                    else -> ioHandler.printMessage("Unknown command")
                }
                continue
            }

            if (input.contains("=")) {
                handleAssignment(input)
                continue
            }

            try {
                val tokens = parser.parse(input)
                if (tokens.isNotEmpty()) {
                    val postfix = converter.convert(tokens)
                    val result = calculator.evaluate(postfix)
                    ioHandler.printMessage(result.toString())
                }
            } catch (e: InvalidIdentifierException) {
                ioHandler.printMessage("Invalid identifier")
            } catch (e: UnknownVariableException) {
                ioHandler.printMessage("Unknown variable")
            } catch (e: Exception) {
                ioHandler.printMessage("Invalid expression")
            }
        }
    }

    private fun handleAssignment(input: String) {
        val parts = input.split("=")
        if (parts.size > 2 || parts[1].trim().isEmpty()) {
            ioHandler.printMessage("Invalid assignment")
            return
        }

        val left = parts[0].trim()
        val right = parts[1].trim()

        if (!left.matches(Regex("[a-zA-Z]+"))) {
            ioHandler.printMessage("Invalid identifier")
            return
        }

        try {
            val tokens = parser.parse(right)
            val postfix = converter.convert(tokens)
            val result = calculator.evaluate(postfix)
            memory.set(left, result)
        } catch (e: UnknownVariableException) {
            ioHandler.printMessage("Unknown variable")
        } catch (e: InvalidIdentifierException) {
            ioHandler.printMessage("Invalid assignment")
        } catch (e: Exception) {
            ioHandler.printMessage("Invalid assignment")
        }
    }
}

fun main() {
    val memory = Memory()
    val parser = ExpressionParser()
    val converter = InfixToPostfixConverter()
    val calculator = Calculator(memory)
    val app = CalculatorApp(IOHandler(), parser, converter, calculator, memory)
    app.run()
}