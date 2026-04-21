package com.rayyan.tesseract.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the sandbox's Python subset.
 * Grammar (informal):
 *
 * <pre>{@code
 * module      = stmt*
 * stmt        = simple_stmt | compound_stmt
 * simple_stmt = (assign | augassign | expr | return | pass | break | continue) NEWLINE
 * assign      = target '=' expr
 * augassign   = target ('+='|'-='|'*='|'/='|'//='|'**='|'%=') expr
 * target      = NAME ( '[' expr ']' )*          // simple + index
 * compound    = if_stmt | while_stmt | for_stmt | func_def
 * block       = NEWLINE INDENT stmt+ DEDENT
 * expr        = or_expr
 * or_expr     = and_expr ('or' and_expr)*
 * and_expr    = not_expr ('and' not_expr)*
 * not_expr    = 'not' not_expr | comparison
 * comparison  = add (cmp_op add)*
 * add         = mul (('+'|'-') mul)*
 * mul         = unary (('*'|'/'|'//'|'%') unary)*
 * unary       = ('-'|'+') unary | power
 * power       = atom_trailer ('**' unary)?
 * atom_trailer= atom (call | index)*
 * call        = '(' (expr (',' expr)*)? ')'
 * index       = '[' expr ']'
 * atom        = NUMBER | STRING | NAME | True | False | None
 *             | '(' expr (',' expr)* ')' | '[' list ']' | '{' dict '}'
 * }</pre>
 */
final class Parser {

    private final List<Token> tokens;
    private int pos;

    Parser(List<Token> tokens) { this.tokens = tokens; }

    Ast.Module parseModule() {
        List<Ast.Stmt> body = new ArrayList<>();
        skipNewlines();
        while (!isAt(Token.Type.EOF)) {
            body.add(parseStmt());
            skipNewlines();
        }
        return new Ast.Module(body);
    }

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    private Ast.Stmt parseStmt() {
        if (isKeyword("if"))     return parseIf();
        if (isKeyword("while"))  return parseWhile();
        if (isKeyword("for"))    return parseFor();
        if (isKeyword("def"))    return parseFuncDef();
        if (isKeyword("return")) return parseReturn();
        if (isKeyword("pass"))   { int ln = peek().line(); advance(); expectNewline(); return new Ast.PassStmt(ln); }
        if (isKeyword("break"))  { int ln = peek().line(); advance(); expectNewline(); return new Ast.BreakStmt(ln); }
        if (isKeyword("continue")) { int ln = peek().line(); advance(); expectNewline(); return new Ast.ContinueStmt(ln); }
        return parseSimpleOrAssign();
    }

    private Ast.Stmt parseSimpleOrAssign() {
        int startPos = pos;
        int ln = peek().line();
        Ast.Expr first = parseExpr();
        if (isOp("=")) {
            advance();
            Ast.Expr value = parseExpr();
            expectNewline();
            if (!(first instanceof Ast.NameRef) && !(first instanceof Ast.Index)) {
                throw new SandboxError("invalid assignment target", ln);
            }
            return new Ast.Assign(first, value, ln);
        }
        for (String op : new String[]{"+=", "-=", "*=", "/=", "//=", "**=", "%="}) {
            if (isOp(op)) {
                advance();
                Ast.Expr value = parseExpr();
                expectNewline();
                if (!(first instanceof Ast.NameRef) && !(first instanceof Ast.Index)) {
                    throw new SandboxError("invalid augmented assignment target", ln);
                }
                return new Ast.AugAssign(first, op, value, ln);
            }
        }
        expectNewline();
        return new Ast.ExprStmt(first, ln);
    }

    private Ast.Stmt parseIf() {
        int ln = peek().line();
        List<Ast.Expr> conditions = new ArrayList<>();
        List<List<Ast.Stmt>> branches = new ArrayList<>();
        List<Ast.Stmt> elseBranch = null;

        expectKeyword("if");
        conditions.add(parseExpr());
        expectOp(":");
        branches.add(parseBlock());
        while (isKeyword("elif")) {
            advance();
            conditions.add(parseExpr());
            expectOp(":");
            branches.add(parseBlock());
        }
        if (isKeyword("else")) {
            advance();
            expectOp(":");
            elseBranch = parseBlock();
        }
        return new Ast.IfStmt(conditions, branches, elseBranch, ln);
    }

    private Ast.Stmt parseWhile() {
        int ln = peek().line();
        expectKeyword("while");
        Ast.Expr cond = parseExpr();
        expectOp(":");
        List<Ast.Stmt> body = parseBlock();
        return new Ast.WhileStmt(cond, body, ln);
    }

    private Ast.Stmt parseFor() {
        int ln = peek().line();
        expectKeyword("for");
        if (!isAt(Token.Type.NAME)) throw new SandboxError("expected loop variable", ln);
        String varName = peek().text();
        advance();
        expectKeyword("in");
        Ast.Expr iter = parseExpr();
        expectOp(":");
        List<Ast.Stmt> body = parseBlock();
        return new Ast.ForStmt(varName, iter, body, ln);
    }

    private Ast.Stmt parseFuncDef() {
        int ln = peek().line();
        expectKeyword("def");
        if (!isAt(Token.Type.NAME)) throw new SandboxError("expected function name", ln);
        String name = peek().text();
        advance();
        expectOp("(");
        List<String> params = new ArrayList<>();
        if (!isOp(")")) {
            params.add(expectName());
            while (isOp(",")) { advance(); params.add(expectName()); }
        }
        expectOp(")");
        expectOp(":");
        List<Ast.Stmt> body = parseBlock();
        return new Ast.FuncDef(name, params, body, ln);
    }

    private Ast.Stmt parseReturn() {
        int ln = peek().line();
        expectKeyword("return");
        Ast.Expr value = null;
        if (!isAt(Token.Type.NEWLINE)) value = parseExpr();
        expectNewline();
        return new Ast.ReturnStmt(value, ln);
    }

    private List<Ast.Stmt> parseBlock() {
        expectNewline();
        expect(Token.Type.INDENT);
        List<Ast.Stmt> stmts = new ArrayList<>();
        skipNewlines();
        while (!isAt(Token.Type.DEDENT) && !isAt(Token.Type.EOF)) {
            stmts.add(parseStmt());
            skipNewlines();
        }
        expect(Token.Type.DEDENT);
        if (stmts.isEmpty()) {
            throw new SandboxError("empty block", peek().line());
        }
        return stmts;
    }

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    private Ast.Expr parseExpr() { return parseOr(); }

    private Ast.Expr parseOr() {
        Ast.Expr left = parseAnd();
        if (!isKeyword("or")) return left;
        List<Ast.Expr> ops = new ArrayList<>();
        ops.add(left);
        int ln = peek().line();
        while (isKeyword("or")) {
            advance();
            ops.add(parseAnd());
        }
        return new Ast.BoolOp("or", ops, ln);
    }

    private Ast.Expr parseAnd() {
        Ast.Expr left = parseNot();
        if (!isKeyword("and")) return left;
        List<Ast.Expr> ops = new ArrayList<>();
        ops.add(left);
        int ln = peek().line();
        while (isKeyword("and")) {
            advance();
            ops.add(parseNot());
        }
        return new Ast.BoolOp("and", ops, ln);
    }

    private Ast.Expr parseNot() {
        if (isKeyword("not")) {
            int ln = peek().line();
            advance();
            return new Ast.UnaryOp("not", parseNot(), ln);
        }
        return parseComparison();
    }

    private Ast.Expr parseComparison() {
        Ast.Expr left = parseAdd();
        String[] cmpOps = {"==", "!=", "<", "<=", ">", ">="};
        if (!matchesAnyOp(cmpOps) && !isKeyword("in") && !isKeyword("not")) return left;
        List<String> ops = new ArrayList<>();
        List<Ast.Expr> operands = new ArrayList<>();
        operands.add(left);
        int ln = peek().line();
        while (true) {
            if (matchesAnyOp(cmpOps)) {
                ops.add(peek().text());
                advance();
                operands.add(parseAdd());
            } else if (isKeyword("in")) {
                advance();
                ops.add("in");
                operands.add(parseAdd());
            } else if (isKeyword("not") && pos + 1 < tokens.size()
                    && tokens.get(pos + 1).type() == Token.Type.KEYWORD
                    && "in".equals(tokens.get(pos + 1).text())) {
                advance(); advance();
                ops.add("not in");
                operands.add(parseAdd());
            } else {
                break;
            }
        }
        return new Ast.Compare(ops, operands, ln);
    }

    private Ast.Expr parseAdd() {
        Ast.Expr left = parseMul();
        while (isOp("+") || isOp("-")) {
            String op = peek().text();
            int ln = peek().line();
            advance();
            Ast.Expr right = parseMul();
            left = new Ast.BinOp(op, left, right, ln);
        }
        return left;
    }

    private Ast.Expr parseMul() {
        Ast.Expr left = parseUnary();
        while (matchesAnyOp(new String[]{"*", "/", "//", "%"})) {
            String op = peek().text();
            int ln = peek().line();
            advance();
            Ast.Expr right = parseUnary();
            left = new Ast.BinOp(op, left, right, ln);
        }
        return left;
    }

    private Ast.Expr parseUnary() {
        if (isOp("-") || isOp("+")) {
            String op = peek().text();
            int ln = peek().line();
            advance();
            return new Ast.UnaryOp(op, parseUnary(), ln);
        }
        return parsePower();
    }

    private Ast.Expr parsePower() {
        Ast.Expr base = parseAtomTrailer();
        if (isOp("**")) {
            int ln = peek().line();
            advance();
            return new Ast.BinOp("**", base, parseUnary(), ln);
        }
        return base;
    }

    private Ast.Expr parseAtomTrailer() {
        Ast.Expr atom = parseAtom();
        while (true) {
            if (isOp("(")) {
                int ln = peek().line();
                advance();
                List<Ast.Expr> args = new ArrayList<>();
                if (!isOp(")")) {
                    args.add(parseExpr());
                    while (isOp(",")) { advance(); if (isOp(")")) break; args.add(parseExpr()); }
                }
                expectOp(")");
                atom = new Ast.Call(atom, args, ln);
            } else if (isOp("[")) {
                int ln = peek().line();
                advance();
                Ast.Expr idx = parseExpr();
                expectOp("]");
                atom = new Ast.Index(atom, idx, ln);
            } else if (isOp(".")) {
                throw new SandboxError("attribute access is not allowed in the sandbox", peek().line());
            } else {
                break;
            }
        }
        return atom;
    }

    private Ast.Expr parseAtom() {
        Token t = peek();
        if (t.type() == Token.Type.NUMBER) { advance(); return new Ast.NumLit(t.value(), t.line()); }
        if (t.type() == Token.Type.STRING) { advance(); return new Ast.StrLit((String) t.value(), t.line()); }
        if (isKeyword("True"))  { advance(); return new Ast.BoolLit(true,  t.line()); }
        if (isKeyword("False")) { advance(); return new Ast.BoolLit(false, t.line()); }
        if (isKeyword("None"))  { advance(); return new Ast.NoneLit(t.line()); }
        if (t.type() == Token.Type.NAME) { advance(); return new Ast.NameRef(t.text(), t.line()); }
        if (isOp("(")) {
            advance();
            if (isOp(")")) { advance(); return new Ast.TupleLit(List.of(), t.line()); }
            Ast.Expr first = parseExpr();
            if (isOp(",")) {
                List<Ast.Expr> items = new ArrayList<>();
                items.add(first);
                while (isOp(",")) { advance(); if (isOp(")")) break; items.add(parseExpr()); }
                expectOp(")");
                return new Ast.TupleLit(items, t.line());
            }
            expectOp(")");
            return first;
        }
        if (isOp("[")) {
            advance();
            List<Ast.Expr> items = new ArrayList<>();
            if (!isOp("]")) {
                items.add(parseExpr());
                while (isOp(",")) { advance(); if (isOp("]")) break; items.add(parseExpr()); }
            }
            expectOp("]");
            return new Ast.ListLit(items, t.line());
        }
        if (isOp("{")) {
            advance();
            List<Ast.Expr> keys = new ArrayList<>();
            List<Ast.Expr> vals = new ArrayList<>();
            if (!isOp("}")) {
                keys.add(parseExpr()); expectOp(":"); vals.add(parseExpr());
                while (isOp(",")) {
                    advance();
                    if (isOp("}")) break;
                    keys.add(parseExpr()); expectOp(":"); vals.add(parseExpr());
                }
            }
            expectOp("}");
            return new Ast.DictLit(keys, vals, t.line());
        }
        throw new SandboxError("unexpected token " + t.text(), t.line());
    }

    // -------------------------------------------------------------------------
    // Token navigation
    // -------------------------------------------------------------------------

    private Token peek() { return tokens.get(pos); }
    private void advance() { pos++; }

    private boolean isAt(Token.Type t) { return peek().type() == t; }
    private boolean isOp(String text) {
        return peek().type() == Token.Type.OP && peek().text().equals(text);
    }
    private boolean isKeyword(String text) {
        return peek().type() == Token.Type.KEYWORD && peek().text().equals(text);
    }
    private boolean matchesAnyOp(String[] ops) {
        if (peek().type() != Token.Type.OP) return false;
        for (String op : ops) if (peek().text().equals(op)) return true;
        return false;
    }

    private void expect(Token.Type t) {
        if (!isAt(t)) throw new SandboxError("expected " + t + ", got " + peek().type() + " '" + peek().text() + "'", peek().line());
        advance();
    }
    private void expectOp(String text) {
        if (!isOp(text)) throw new SandboxError("expected '" + text + "', got '" + peek().text() + "'", peek().line());
        advance();
    }
    private void expectKeyword(String text) {
        if (!isKeyword(text)) throw new SandboxError("expected '" + text + "'", peek().line());
        advance();
    }
    private String expectName() {
        if (!isAt(Token.Type.NAME)) throw new SandboxError("expected identifier", peek().line());
        String n = peek().text();
        advance();
        return n;
    }
    private void expectNewline() {
        if (isAt(Token.Type.NEWLINE)) advance();
        else if (!isAt(Token.Type.EOF) && !isAt(Token.Type.DEDENT)) {
            throw new SandboxError("expected newline, got '" + peek().text() + "'", peek().line());
        }
    }
    private void skipNewlines() {
        while (isAt(Token.Type.NEWLINE)) advance();
    }
}
