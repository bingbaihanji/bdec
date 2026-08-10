package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

/**
 * 各种语句的占位符/桩节点集合.
 *
 * <p>这些类在 AST 重写阶段作为中间占位符使用,
 * 在最终输出前会被展开或替换为实际的语句结构.
 * 每个占位符对应一种特定的语句类型(kind).
 */
final class SwitchStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.SWITCH;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** try 语句占位符 */
final class TryStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.TRY;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** throw 语句占位符 */
final class ThrowStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.THROW;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** break 语句占位符 */
final class BreakStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.BREAK;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** continue 语句占位符 */
final class ContinueStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.CONTINUE;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** 变量声明占位符 */
final class VarDeclStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.VARIABLE_DECL;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** assert 语句占位符 */
final class AssertStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.ASSERT;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** synchronized 语句占位符 */
final class SyncStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.SYNCHRONIZED;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

/** 标签语句占位符 */
final class LabeledStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.LABELED;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
