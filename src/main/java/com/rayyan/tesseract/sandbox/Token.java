package com.rayyan.tesseract.sandbox;

/**
 * Single lexer token. Flat record rather than a class hierarchy — the
 * parser switches on {@link #type} for efficiency and fits easily in
 * the 300–500 LOC sandbox budget.
 */
record Token(Type type, String text, Object value, int line) {

    enum Type {
        NAME, NUMBER, STRING, KEYWORD,
        // operators / punctuation (text field carries the exact lexeme)
        OP,
        NEWLINE, INDENT, DEDENT, EOF
    }

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }
}
