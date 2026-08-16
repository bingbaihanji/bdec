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
import com.bingbaihanji.bdec.ast.expr.InstanceOfExpr;
import com.bingbaihanji.bdec.ast.expr.UnExpr;
import com.bingbaihanji.bdec.ast.expr.UnaryOperator;
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
     * 或内联强转 {@code if(obj instanceof Type) { ... (Type)obj ... }}
     * 合并为:{@code if(obj instanceof Type v) { ... }}
     *
     * <p>模式变量 v 仅在其对应的 then 分支作用域内有效,因此带 else 分支时
     * 也可安全合并(else 分支不引用 v).</p>
     */
    private Statement detectPatternMatch(BlockStatement bs) {
        List<Statement> stmts = new ArrayList<>(bs.statements());
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (!(s instanceof IfStatement ifStmt)) {
                continue;
            }

            // 去否定:!(o instanceof T) 形式,模式变量绑定在 else 分支
            boolean negated = false;
            Expression cond = ifStmt.condition();
            if (cond instanceof UnExpr u && u.operator() == UnaryOperator.NOT) {
                negated = true;
                cond = u.operand();
            }
            // 检查条件:obj instanceof Type(兼容 InstanceOfExpr 与旧 BinExpr 表示)
            InstanceofMatch match = extractInstanceof(cond);
            if (match == null) {
                continue;
            }
            Expression testedObj = match.testExpr;
            String typeName = match.typeName;

            // 模式变量绑定在 instanceof 为真的分支(去否定后):正→then,负→else
            Statement bindBranch = negated ? ifStmt.elseBranch() : ifStmt.thenBranch();
            if (bindBranch == null) {
                continue;
            }
            List<Statement> bindStmts = getBodyStatements(bindBranch);
            if (bindStmts.isEmpty()) {
                continue;
            }
            Statement first = bindStmts.get(0);

            // 模式 A:首条语句为 Type v = (Type) obj; 声明 → 复用声明名
            String varName = extractVarDecl(first, typeName, testedObj);
            List<Statement> newBind;
            if (varName != null) {
                newBind = new ArrayList<>(bindStmts);
                newBind.remove(0);
            } else {
                // 模式 B:强制转型被内联到表达式(如 return (Type) obj;),
                // 变量声明已被 SSA 复制传播消除 → 引入新模式变量并替换内联强转
                varName = freshPatternName(typeName);
                InlineCastReplacer replacer = new InlineCastReplacer(
                        typeName, testedObj, varName);
                newBind = new ArrayList<>();
                for (Statement st : bindStmts) {
                    newBind.add(replacer.transformStmt(st));
                }
                if (!replacer.replaced) {
                    continue;
                }
            }

            // 构建新条件表达式:obj instanceof Type varName
            Expression newCondition = new BinExpr(BinaryOperator.INSTANCEOF,
                    testedObj, new VarExpr(typeName + " " + varName));

            // 构建新绑定分支体(移除强制转型声明语句或已内联强转)
            Statement newBindBody = newBind.size() == 1 ? newBind.get(0)
                    : new BlockStatement(newBind);

            // 去否定后重建:then = 绑定分支,else = 另一分支
            Statement newThen = newBindBody;
            Statement newElse = negated ? ifStmt.thenBranch() : ifStmt.elseBranch();

            stmts.set(i, new IfStatement(newCondition, newThen, newElse));
            return new BlockStatement(stmts);
        }
        return bs;
    }

    /**
     * 提取变量声明:验证语句是否为 {@code Type name = (Type) obj;} 形式
     * (表达式语句赋值或 VariableDeclaration 两种表示).
     *
     * @return 若匹配成功则返回变量名,否则返回 null
     */
    private String extractVarDecl(Statement s, String typeName, Expression testedObj) {
        String varName = null;
        CastExpr cast = null;
        if (s instanceof ExpressionStatement es
                && es.expression() instanceof AssignExpr assign
                && assign.target() instanceof VarExpr var) {
            varName = var.name();
            cast = assign.value() instanceof CastExpr c ? c : null;
        } else if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                && vd.initializer() instanceof CastExpr c) {
            // STORE → VariableDeclaration 路径(如 equals 的 Map<?,?> map = (Map) obj)
            varName = vd.name();
            cast = c;
        }
        if (varName == null || cast == null) {
            return null;
        }
        // 检查强制转型的目标类型是否匹配
        String castType = cast.targetType() != null
                ? cast.targetType().internalName() : null;
        if (castType == null) {
            return null;
        }
        String shortType = castType.contains("/")
                ? castType.substring(castType.lastIndexOf('/') + 1) : castType;
        if (!shortType.equals(typeName)) {
            return null;
        }
        return varName;
    }

    /** 提取语句中的子语句列表(若为块语句则展开,否则包装为单元素列表) */
    private List<Statement> getBodyStatements(Statement s) {
        if (s instanceof BlockStatement bs) {
            return new ArrayList<>(bs.statements());
        }
        return new ArrayList<>(List.of(s));
    }

    /** 为模式变量生成全新名称:类型简单名首字母小写(String → string,
     *  Integer → integer).原始变量名已被复制传播消除,无法从 AST 恢复. */
    private String freshPatternName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "v";
        }
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
    }

    /** 从条件表达式中提取 instanceof 的被测对象与类型简单名,兼容
     *  {@link InstanceOfExpr}(BlockReducer 现代表示)与旧 {@code BinExpr(INSTANCEOF,...)}. */
    private InstanceofMatch extractInstanceof(Expression cond) {
        if (cond instanceof BinExpr be && be.operator() == BinaryOperator.INSTANCEOF
                && be.right() instanceof VarExpr typeVar) {
            return new InstanceofMatch(be.left(), typeVar.name());
        }
        if (cond instanceof InstanceOfExpr ioe && ioe.targetType() != null
                && ioe.targetType().internalName() != null) {
            return new InstanceofMatch(ioe.operand(),
                    simplifyTypeName(ioe.targetType().internalName()));
        }
        return null;
    }

    /** 内部名简化为类型简单名(java/lang/String → String). */
    private String simplifyTypeName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        int dollar = internalName.lastIndexOf('$');
        int cut = Math.max(slash, dollar);
        return cut >= 0 ? internalName.substring(cut + 1) : internalName;
    }

    /** instanceof 提取结果:被测对象表达式 + 类型简单名. */
    private record InstanceofMatch(Expression testExpr, String typeName) {}

    /**
     * 内联强转替换器:把 then 分支体中的 {@code (Type) testedObj} 强转
     * 替换为模式变量引用 {@code varName},并记录是否发生替换.
     */
    private static final class InlineCastReplacer extends AstTransformer {

        private final String typeName;

        private final Expression testedObj;

        private final String varName;

        private boolean replaced = false;

        InlineCastReplacer(String typeName, Expression testedObj, String varName) {
            this.typeName = typeName;
            this.testedObj = testedObj;
            this.varName = varName;
        }

        @Override
        protected Expression transformCast(CastExpr e) {
            if (matchesType(e.targetType()) && sameExpr(e.operand(), testedObj)) {
                replaced = true;
                return new VarExpr(varName);
            }
            return super.transformCast(e);
        }

        /** 强转目标类型的简单名是否匹配 instanceof 的类型名 */
        private boolean matchesType(com.bingbaihanji.bdec.type.JavaType t) {
            if (t == null || t.internalName() == null) {
                return false;
            }
            String internal = t.internalName();
            String shortName = internal.contains("/")
                    ? internal.substring(internal.lastIndexOf('/') + 1) : internal;
            int dollar = shortName.lastIndexOf('$');
            if (dollar >= 0) {
                shortName = shortName.substring(dollar + 1);
            }
            return shortName.equals(typeName);
        }

        /** 强转操作数是否与 instanceof 的被测对象一致(常见为同名 VarExpr) */
        private boolean sameExpr(Expression a, Expression b) {
            if (a instanceof VarExpr va && b instanceof VarExpr vb) {
                return va.name().equals(vb.name());
            }
            return a == b;
        }
    }
}
