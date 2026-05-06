package com.yuyu.fishagent.agent.tool.builtin;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;

/**
 * 数学计算器工具：支持 +,-,*,/,(,) 与小数；不依赖外部脚本引擎，零信任安全输入。
 */
@Component
public class CalculatorToolProvider implements AgentToolProvider {

    public record Input(String expression) {}

    public record Output(String expression, String result) {}

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> {
            if (input == null || input.expression() == null || input.expression().isBlank()) {
                return new Output("", "ERROR: expression is required");
            }
            try {
                BigDecimal result = evaluate(input.expression());
                return new Output(input.expression(), result.stripTrailingZeros().toPlainString());
            } catch (ArithmeticException ae) {
                return new Output(input.expression(), "ERROR: " + ae.getMessage());
            } catch (Exception e) {
                return new Output(input.expression(), "ERROR: invalid expression - " + e.getMessage());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("数学表达式求值。支持 + - * / 与括号、小数。例如：(1+2)*3.5。")
                .inputType(Input.class)
                .build();
    }

    /**
     * 用双栈实现的 Shunting-yard 求值器，避免引入 ScriptEngine 带来的安全风险。
     */
    private static BigDecimal evaluate(String expr) {
        Deque<BigDecimal> nums = new ArrayDeque<>();
        Deque<Character> ops = new ArrayDeque<>();
        int i = 0;
        int n = expr.length();
        boolean expectUnary = true;
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || c == '.') {
                int j = i;
                while (j < n && (Character.isDigit(expr.charAt(j)) || expr.charAt(j) == '.')) {
                    j++;
                }
                nums.push(new BigDecimal(expr.substring(i, j)));
                i = j;
                expectUnary = false;
            } else if (c == '(') {
                ops.push(c);
                i++;
                expectUnary = true;
            } else if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') {
                    apply(nums, ops.pop());
                }
                if (ops.isEmpty()) {
                    throw new IllegalArgumentException("unmatched ')'");
                }
                ops.pop();
                i++;
                expectUnary = false;
            } else if (isOp(c)) {
                if (expectUnary && (c == '+' || c == '-')) {
                    nums.push(BigDecimal.ZERO);
                }
                while (!ops.isEmpty() && ops.peek() != '(' && precedence(ops.peek()) >= precedence(c)) {
                    apply(nums, ops.pop());
                }
                ops.push(c);
                i++;
                expectUnary = true;
            } else {
                throw new IllegalArgumentException("unexpected char: " + c);
            }
        }
        while (!ops.isEmpty()) {
            char op = ops.pop();
            if (op == '(' || op == ')') {
                throw new IllegalArgumentException("mismatched parentheses");
            }
            apply(nums, op);
        }
        if (nums.size() != 1) {
            throw new IllegalArgumentException("invalid expression");
        }
        return nums.pop();
    }

    private static boolean isOp(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    private static int precedence(char c) {
        return switch (c) {
            case '+', '-' -> 1;
            case '*', '/' -> 2;
            default -> 0;
        };
    }

    private static void apply(Deque<BigDecimal> nums, char op) {
        if (nums.size() < 2) {
            throw new IllegalArgumentException("operator " + op + " missing operand");
        }
        BigDecimal b = nums.pop();
        BigDecimal a = nums.pop();
        BigDecimal r = switch (op) {
            case '+' -> a.add(b);
            case '-' -> a.subtract(b);
            case '*' -> a.multiply(b);
            case '/' -> {
                if (b.signum() == 0) {
                    throw new ArithmeticException("divide by zero");
                }
                yield a.divide(b, new MathContext(20, RoundingMode.HALF_UP));
            }
            default -> throw new IllegalArgumentException("unknown op: " + op);
        };
        nums.push(r);
    }
}
