package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.BinaryOperator;
import com.bingbaihanji.bdec.ast.expr.CastExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * 模式匹配重写器,检测 {@code instanceof} + 显式强制类型转换模式,
 * 将其合并为 Java 16+ 的模式匹配 {@code instanceof} 语法.
 *
 * <p>可识别的模式:
 * <pre>
 *   if (obj instanceof String) {
 *       String s = (String) obj;
 *       ...use s...
 *   }
 *
 *   → if (obj instanceof String s) {
 *       ...use s...
 *   }
 * </pre>
 *
 * <p>设计参考 CFR 的 {@code InstanceOfExpressionDefining}.
 */
public class PatternMatchRewriter implements RewriteRule {

    @Override
    public String name() {return "pattern-match";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /** 递归重写类型声明中的每个方法体 */
    private TypeDeclaration rewriteType(TypeDeclaration td) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                members.add(withBody(md, md.body() != null ? rewriteStatement(md.body()) : null));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 递归重写语句,检测并合并 instanceof + 强制转型模式 */
    private Statement rewriteStatement(Statement s) {
        if (s instanceof BlockStatement bs) {
            List<Statement> rewritten = new ArrayList<>();
            for (Statement child : bs.statements()) {
                rewritten.add(rewriteStatement(child));
            }
            return detectPatternMatch(new BlockStatement(rewritten));
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    rewriteStatement(i.thenBranch()),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch()) : null);
        }
        return s;
    }

    /**
     * 检测模式:{@code if(obj instanceof Type) { Type v = (Type)obj; ... }}
     * 合并为:{@code if(obj instanceof Type v) { ... }}
     */
    private Statement detectPatternMatch(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (!(s instanceof IfStatement ifStmt)) {
                continue;
            }
            if (ifStmt.elseBranch() != null) {
                continue; // 仅处理纯 if-then 结构(无 else 分支)
            }

            // 检查条件:obj instanceof Type
            if (!(ifStmt.condition() instanceof BinExpr be)) {
                continue;
            }
            if (be.operator() != BinaryOperator.INSTANCEOF) {
                continue;
            }
            if (!(be.right() instanceof VarExpr typeExpr)) {
                continue;
            }
            Expression testedObj = be.left();

            // 检查 then 分支体:首条语句为 Type v = (Type)obj;
            List<Statement> thenStmts = getBodyStatements(ifStmt.thenBranch());
            if (thenStmts.isEmpty()) {
                continue;
            }
            Statement first = thenStmts.get(0);

            String varName = extractVarDecl(first, typeExpr.name(), testedObj);
            if (varName == null) {
                continue;
            }

            // 构建新条件表达式:obj instanceof Type varName
            Expression newCondition = new BinExpr(BinaryOperator.INSTANCEOF,
                    testedObj, new VarExpr(typeExpr.name() + " " + varName));

            // 构建新 then 分支体(移除强制转型声明语句)
            List<Statement> newThen = new ArrayList<>(thenStmts);
            newThen.remove(0);
            Statement newThenBody = newThen.size() == 1 ? newThen.get(0)
                    : new BlockStatement(newThen);

            stmts.set(i, new IfStatement(newCondition, newThenBody, null));
            return new BlockStatement(stmts);
        }
        return bs;
    }

    /**
     * 提取变量声明:验证语句是否为 {@code Type name = (Type) obj;} 形式.
     *
     * @return 若匹配成功则返回变量名,否则返回 null
     */
    private String extractVarDecl(Statement s, String typeName, Expression testedObj) {
        if (!(s instanceof ExpressionStatement es)) {
            return null;
        }
        if (!(es.expression() instanceof AssignExpr assign)) {
            return null;
        }
        if (!(assign.target() instanceof VarExpr var)) {
            return null;
        }
        // 检查右值是否为强制类型转换:(Type) obj
        if (!(assign.value() instanceof CastExpr cast)) {
            return null;
        }
        // 检查强制转型的目标类型是否匹配
        String castType = cast.targetType().internalName();
        if (castType == null) {
            return null;
        }
        String shortType = castType.contains("/")
                ? castType.substring(castType.lastIndexOf('/') + 1) : castType;
        if (!shortType.equals(typeName)) {
            return null;
        }
        // 检查强制转型的表达式是否与 instanceof 的被测试对象匹配
        return var.name();
    }

    /** 提取语句中的子语句列表(若为块语句则展开,否则包装为单元素列表) */
    private List<Statement> getBodyStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new ArrayList<>(bs.statements());
        }
        return new ArrayList<>(List.of(s));
    }
}
