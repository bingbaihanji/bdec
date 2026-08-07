package com.bingbaihanji.bdec.ast.stmt;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;

import java.util.List;

final class SwitchStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.SWITCH;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class TryStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.TRY;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class ThrowStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.THROW;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class BreakStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.BREAK;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class ContinueStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.CONTINUE;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class VarDeclStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.VARIABLE_DECL;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class AssertStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.ASSERT;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class SyncStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.SYNCHRONIZED;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}

final class LabeledStmt extends Statement {

    @Override
    public AstKind kind() {return AstKind.LABELED;}

    @Override
    public List<AstNode> children() {return List.of();}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitStatement(this, c);}
}
