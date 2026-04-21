package com.rayyan.tesseract.sandbox;

import java.util.List;

/**
 * AST node types for the sandbox's Python subset. Nested sealed
 * hierarchies kept in one file to minimise sprawl — every node has a
 * {@code line} for error reporting.
 *
 * <p>Expression nodes evaluate to Java Objects; statement nodes have
 * side effects on the interpreter's environment.
 */
final class Ast {

    private Ast() {}

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    sealed interface Expr permits NumLit, StrLit, BoolLit, NoneLit, NameRef,
            BinOp, UnaryOp, BoolOp, Compare, Call, Index, ListLit, DictLit, TupleLit {
        int line();
    }

    record NumLit(Object value, int line) implements Expr {}
    record StrLit(String value, int line) implements Expr {}
    record BoolLit(boolean value, int line) implements Expr {}
    record NoneLit(int line) implements Expr {}
    record NameRef(String name, int line) implements Expr {}
    record BinOp(String op, Expr left, Expr right, int line) implements Expr {}
    record UnaryOp(String op, Expr operand, int line) implements Expr {}
    record BoolOp(String op, List<Expr> operands, int line) implements Expr {}
    record Compare(List<String> ops, List<Expr> operands, int line) implements Expr {}
    record Call(Expr target, List<Expr> args, int line) implements Expr {}
    record Index(Expr target, Expr index, int line) implements Expr {}
    record ListLit(List<Expr> items, int line) implements Expr {}
    record DictLit(List<Expr> keys, List<Expr> values, int line) implements Expr {}
    record TupleLit(List<Expr> items, int line) implements Expr {}

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    sealed interface Stmt permits Assign, AugAssign, ExprStmt, IfStmt, WhileStmt,
            ForStmt, FuncDef, ReturnStmt, PassStmt, BreakStmt, ContinueStmt {
        int line();
    }

    record Assign(Expr target, Expr value, int line) implements Stmt {}
    record AugAssign(Expr target, String op, Expr value, int line) implements Stmt {}
    record ExprStmt(Expr expr, int line) implements Stmt {}
    record IfStmt(List<Expr> conditions, List<List<Stmt>> branches, List<Stmt> elseBranch, int line) implements Stmt {}
    record WhileStmt(Expr condition, List<Stmt> body, int line) implements Stmt {}
    record ForStmt(String var, Expr iter, List<Stmt> body, int line) implements Stmt {}
    record FuncDef(String name, List<String> params, List<Stmt> body, int line) implements Stmt {}
    record ReturnStmt(Expr value, int line) implements Stmt {}
    record PassStmt(int line) implements Stmt {}
    record BreakStmt(int line) implements Stmt {}
    record ContinueStmt(int line) implements Stmt {}

    record Module(List<Stmt> body) {}
}
