package com.rayyan.tesseract.sandbox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Python-subset lexer with significant-whitespace handling. Emits
 * {@link Token.Type#INDENT} / {@link Token.Type#DEDENT} tokens so the
 * parser can treat blocks as ordinary bracketed structures.
 *
 * <p>Handles:
 * <ul>
 *   <li>integer and float literals</li>
 *   <li>single- and double-quoted string literals with basic escapes
 *       ({@code \\n \\t \\" \\\\})</li>
 *   <li>identifiers and keywords</li>
 *   <li>multi-char operators {@code == != <= >= // ** += -= *= /= //=}</li>
 *   <li>line comments starting with {@code #}</li>
 *   <li>implicit line continuation inside brackets</li>
 * </ul>
 *
 * <p>Whitespace is space-only; tabs are treated as one space but a
 * mix of tabs and spaces on the same file throws. This keeps the
 * indent comparison unambiguous without implementing Python's tab-stop
 * rules (which are neither well-known nor deterministic in practice).
 */
final class Lexer {

    private static final Set<String> KEYWORDS = Set.of(
            "def", "return", "if", "elif", "else",
            "for", "while", "in", "not", "and", "or",
            "True", "False", "None", "pass", "break", "continue"
    );

    private final String src;
    private int pos;
    private int line = 1;
    private int bracketDepth = 0;
    private final Deque<Integer> indents = new ArrayDeque<>();
    private boolean sawTab = false;
    private boolean sawSpace = false;

    Lexer(String src) {
        this.src = src;
        indents.push(0);
    }

    List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        boolean atLineStart = true;
        while (pos < src.length()) {
            if (atLineStart && bracketDepth == 0) {
                handleIndent(out);
                atLineStart = false;
                // if handleIndent skipped a blank line, pos may now be
                // past a newline and we should loop around again.
                if (pos < src.length() && src.charAt(pos) == '\n') {
                    pos++;
                    line++;
                    atLineStart = true;
                    continue;
                }
            }
            if (pos >= src.length()) break;
            char c = src.charAt(pos);

            if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                continue;
            }
            if (c == '\n') {
                if (bracketDepth == 0) {
                    if (!out.isEmpty() && out.get(out.size() - 1).type() != Token.Type.NEWLINE) {
                        out.add(new Token(Token.Type.NEWLINE, "\\n", null, line));
                    }
                    atLineStart = true;
                }
                pos++;
                line++;
                continue;
            }
            if (c == ' ' || c == '\t') { pos++; continue; }
            if (c == '\\' && pos + 1 < src.length() && src.charAt(pos + 1) == '\n') {
                pos += 2; line++; continue;
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
                out.add(readNumber());
                continue;
            }
            if (c == '"' || c == '\'') {
                out.add(readString(c));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                out.add(readIdentifier());
                continue;
            }
            out.add(readOperator(c));
        }

        if (!out.isEmpty() && out.get(out.size() - 1).type() != Token.Type.NEWLINE) {
            out.add(new Token(Token.Type.NEWLINE, "\\n", null, line));
        }
        while (indents.size() > 1) {
            indents.pop();
            out.add(new Token(Token.Type.DEDENT, "", null, line));
        }
        out.add(new Token(Token.Type.EOF, "", null, line));
        return out;
    }

    private void handleIndent(List<Token> out) {
        int width = 0;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ') { sawSpace = true; width++; pos++; }
            else if (c == '\t') { sawTab = true; width++; pos++; }
            else break;
        }
        if (sawTab && sawSpace) {
            throw new SandboxError("mixed tabs and spaces in indentation", line);
        }
        // blank line or comment-only line: ignore
        if (pos >= src.length()) return;
        char c = src.charAt(pos);
        if (c == '\n' || c == '#') return;

        int current = indents.peek();
        if (width > current) {
            indents.push(width);
            out.add(new Token(Token.Type.INDENT, "", null, line));
        } else {
            while (width < indents.peek()) {
                indents.pop();
                out.add(new Token(Token.Type.DEDENT, "", null, line));
            }
            if (width != indents.peek()) {
                throw new SandboxError("inconsistent dedent", line);
            }
        }
    }

    private Token readNumber() {
        int start = pos;
        boolean isFloat = false;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        if (pos < src.length() && src.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        String text = src.substring(start, pos);
        Object value = isFloat ? Double.parseDouble(text) : Long.parseLong(text);
        return new Token(Token.Type.NUMBER, text, value, line);
    }

    private Token readString(char quote) {
        int startLine = line;
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != quote) {
            char c = src.charAt(pos);
            if (c == '\\') {
                if (pos + 1 >= src.length()) throw new SandboxError("unterminated escape", startLine);
                char next = src.charAt(pos + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case '0' -> sb.append('\0');
                    default -> sb.append(next);
                }
                pos += 2;
            } else if (c == '\n') {
                throw new SandboxError("unterminated string literal", startLine);
            } else {
                sb.append(c);
                pos++;
            }
        }
        if (pos >= src.length()) throw new SandboxError("unterminated string literal", startLine);
        pos++;
        return new Token(Token.Type.STRING, sb.toString(), sb.toString(), startLine);
    }

    private Token readIdentifier() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        String text = src.substring(start, pos);
        if (KEYWORDS.contains(text)) {
            return new Token(Token.Type.KEYWORD, text, null, line);
        }
        return new Token(Token.Type.NAME, text, null, line);
    }

    private Token readOperator(char c) {
        String two = pos + 1 < src.length() ? src.substring(pos, pos + 2) : "";
        String three = pos + 2 < src.length() ? src.substring(pos, pos + 3) : "";
        if (three.equals("//=") || three.equals("**=")) {
            pos += 3;
            return new Token(Token.Type.OP, three, null, line);
        }
        if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                || two.equals("//") || two.equals("**")
                || two.equals("+=") || two.equals("-=") || two.equals("*=") || two.equals("/=")
                || two.equals("%=")) {
            pos += 2;
            return new Token(Token.Type.OP, two, null, line);
        }
        if ("()[]{}".indexOf(c) >= 0) {
            if (c == '(' || c == '[' || c == '{') bracketDepth++;
            else bracketDepth--;
        }
        if ("+-*/%<>=(),:[]{}.;".indexOf(c) < 0) {
            throw new SandboxError("unexpected character '" + c + "'", line);
        }
        pos++;
        return new Token(Token.Type.OP, String.valueOf(c), null, line);
    }
}
