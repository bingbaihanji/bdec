package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.AssignExpr;
import com.bingbaihanji.bdec.ast.expr.BinExpr;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.FieldAccessExpr;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.LoopStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.ThrowStatement;
import com.bingbaihanji.bdec.ast.stmt.TryStatement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 源码清理重写器,作为最小安全网修复反编译产生的编译错误.
 * <p>
 * 仅修复会导致编译错误的模式,包括:
 * </p>
 * <ol>
 *   <li>包装在 return 语句中的 void 方法调用 —— 拆分为独立调用加默认值返回</li>
 *   <li>throw 语句中未声明的异常变量 —— 自动声明为 Throwable 类型</li>
 *   <li>同一作用域内的重复变量声明 —— 转换为赋值语句</li>
 *   <li>未声明的局部变量 —— 使用安全的默认值自动声明,避免字段遮蔽</li>
 * </ol>
 */
public class SourceCleanup implements RewriteRule {

    /**
     * 收集类型声明中的所有字段名称,用于防止变量自动声明时发生字段遮蔽.
     *
     * @param td 待收集的类型声明
     * @return 字段名称集合
     */
    private static Set<String> collectFieldNames(TypeDeclaration td) {
        Set<String> names = new HashSet<>();
        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd && fd.name() != null) {
                names.add(fd.name());
            }
        }
        return names;
    }

    /**
     * 检查名称是否看起来像类名(以大写字母开头).
     * 像 "Math" 或 "String" 这样的静态方法目标不应被自动声明为局部变量.
     *
     * @param name 待检查的名称
     * @return 若以大写字母开头返回 {@code true}
     */
    private static boolean looksLikeClassName(String name) {
        return name != null && !name.isEmpty()
                && Character.isUpperCase(name.charAt(0));
    }

    @Override
    public String name() {return "source-cleanup";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext ctx) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(cleanupType(td));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 清理单个类型声明中的所有方法体,修复常见的编译错误模式.
     *
     * @param td 待清理的类型声明
     * @return 清理后的类型声明
     */
    private TypeDeclaration cleanupType(TypeDeclaration td) {
        Set<String> fieldNames = collectFieldNames(td);
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md && md.body() != null) {
                boolean nonVoid = md.returnType() != null
                        && md.returnType().kind() != TypeKind.VOID;
                Set<String> paramNames = new HashSet<>(java.util.Arrays.asList(md.parameterNames()));
                members.add(new MethodDeclaration(md.accessFlags(), md.name(),
                        md.returnType(), md.parameterNames(), md.parameterTypes(),
                        md.typeParameters(),
                        fix(md.body(), nonVoid, md.returnType(), fieldNames, paramNames)));
            } else {
                members.add(m);
            }
        }
        return new TypeDeclaration(td.accessFlags(), td.simpleName(), td.kindName(),
                td.superName(), td.interfaceNames(), td.typeParameters(), members);
    }

    /**
     * 递归修复语句中的编译错误模式.
     *
     * @param s         待修复的语句
     * @param nonVoid   方法是否有非 void 返回类型
     * @param retType   方法的返回类型
     * @param fieldNames 当前类的字段名集合,用于避免字段遮蔽
     * @param paramNames 方法参数名集合,用于避免重复声明
     * @return 修复后的语句
     */
    private Statement fix(Statement s, boolean nonVoid, JavaType retType,
                          Set<String> fieldNames, Set<String> paramNames) {
        if (s == null) {
            return null;
        }
        // 修复模式1:非 void 方法中的 return void 方法调用 → 拆分为调用 + 默认值 return
        if (s instanceof ReturnStatement rs && rs.value() != null && nonVoid) {
            Expression v = rs.value();
            if (v instanceof InvocationExpr inv && isVoid(inv)) {
                return new BlockStatement(List.of(
                        new ExpressionStatement(v),
                        new ReturnStatement(defaultVal(retType))));
            }
            return s;
        }
        // 修复模式2:throw varN 且变量未声明 → 自动声明 Throwable 类型变量
        if (s instanceof ThrowStatement ts && ts.expression() instanceof VarExpr ve
                && ve.name().startsWith("var")) {
            return new BlockStatement(List.of(
                    new VariableDeclaration(JavaType.classType("java/lang/Throwable"),
                            ve.name(), null), s));
        }
        if (s instanceof BlockStatement bs) {
            // 截断 return/throw 之后的死代码(记录模式去糖化后可能遗留)
            List<Statement> stmts = truncateAfterTerminator(bs.statements());

            List<Statement> cleaned = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // 第一趟:收集当前块(含嵌套块)中所有已声明的变量名
            Set<String> allDeclared = new HashSet<>();
            collectDeclared(new BlockStatement(stmts), allDeclared);
            // 将字段名也加入已声明集合,避免自动声明时遮蔽字段
            allDeclared.addAll(fieldNames);
            // 将参数名也加入已声明集合,避免重复声明参数
            allDeclared.addAll(paramNames);
            for (Statement c : stmts) {
                // 修复模式3:重复变量声明 → 转换为赋值语句
                if (c instanceof VariableDeclaration vd) {
                    if (!seen.add(vd.name()) && vd.initializer() != null) {
                        cleaned.add(new ExpressionStatement(
                                new AssignExpr(new VarExpr(vd.name()), vd.initializer())));
                        continue;
                    }
                }
                // 修复模式4:在语句执行前检查是否存在未声明的变量引用
                Set<String> used = new HashSet<>();
                collectVarNames(c, used);
                for (String u : used) {
                    if (!allDeclared.contains(u) && !seen.contains(u)
                            && !isBuiltin(u) && !looksLikeClassName(u)) {
                        // 使用默认值自动声明(整型默认为 0,对象默认为 null)
                        cleaned.add(new VariableDeclaration(
                                JavaType.INT, u,
                                new LitExpr(0, JavaType.INT)));
                        seen.add(u);
                        allDeclared.add(u);
                    }
                }
                cleaned.add(fix(c, nonVoid, retType, fieldNames, paramNames));
            }
            return new BlockStatement(cleaned);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(i.condition(),
                    fix(i.thenBranch(), nonVoid, retType, fieldNames, paramNames),
                    i.elseBranch() != null
                            ? fix(i.elseBranch(), nonVoid, retType, fieldNames, paramNames) : null);
        }
        if (s instanceof LoopStatement l) {
            return new LoopStatement(l.loopKind(), l.condition(),
                    fix(l.body(), nonVoid, retType, fieldNames, paramNames));
        }
        if (s instanceof TryStatement t) {
            List<TryStatement.CatchClause> cc = new ArrayList<>();
            for (var c : t.catchClauses()) {
                // 将 catch 子句的异常变量名纳入已声明集合,
                // 防止自动声明逻辑在 catch 体内重复声明该变量
                Set<String> extParams = new HashSet<>(paramNames);
                extParams.add(c.varName());
                cc.add(new TryStatement.CatchClause(c.exceptionType(), c.varName(),
                        fix(c.body(), nonVoid, retType, fieldNames, extParams)));
            }
            return new TryStatement(fix(t.tryBody(), nonVoid, retType, fieldNames, paramNames), cc,
                    t.finallyBody() != null
                            ? fix(t.finallyBody(), nonVoid, retType, fieldNames, paramNames) : null);
        }
        return s;
    }

    /**
     * 判断方法调用表达式是否为 void 返回类型.
     *
     * @param inv 方法调用表达式
     * @return 若返回类型为 void 则返回 {@code true}
     */
    private boolean isVoid(InvocationExpr inv) {
        return inv.returnType() != null && inv.returnType().kind() == TypeKind.VOID;
    }

    /**
     * 截断 return/throw 之后的死代码.
     *
     * <p>记录模式去糖化和 MatchException 处理器抑制后,return 语句后
     * 可能遗留不可达语句,导致类型不匹配和重复声明错误.
     */
    private static List<Statement> truncateAfterTerminator(List<Statement> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof ReturnStatement || s instanceof ThrowStatement) {
                if (i + 1 < stmts.size()) {
                    return new ArrayList<>(stmts.subList(0, i + 1));
                }
            }
        }
        return stmts;
    }

    /**
     * 递归收集语句中声明的所有变量名.
     *
     * @param s   待遍历的语句
     * @param out 输出集合,收集到的变量名将添加至此
     */
    private void collectDeclared(Statement s, Set<String> out) {
        if (s instanceof VariableDeclaration vd) {
            out.add(vd.name());
        } else if (s instanceof BlockStatement bs) {
            bs.statements().forEach(c -> collectDeclared(c, out));
        } else if (s instanceof IfStatement i) {
            collectDeclared(i.thenBranch(), out);
            if (i.elseBranch() != null) {
                collectDeclared(i.elseBranch(), out);
            }
        } else if (s instanceof LoopStatement l) {
            collectDeclared(l.body(), out);
        } else if (s instanceof TryStatement t) {
            collectDeclared(t.tryBody(), out);
            for (var c : t.catchClauses()) {
                out.add(c.varName()); // catch clause parameter is a declared variable
                collectDeclared(c.body(), out);
            }
            if (t.finallyBody() != null) {
                collectDeclared(t.finallyBody(), out);
            }
        }
    }

    /**
     * 递归收集语句中引用的所有变量名.
     *
     * @param s   待遍历的语句
     * @param out 输出集合,收集到的变量名将添加至此
     */
    private void collectVarNames(Statement s, Set<String> out) {
        if (s instanceof ExpressionStatement es) {
            collectVarNamesInExpr(es.expression(), out);
        } else if (s instanceof ReturnStatement rs && rs.value() != null) {
            collectVarNamesInExpr(rs.value(), out);
        } else if (s instanceof ThrowStatement ts && ts.expression() != null) {
            collectVarNamesInExpr(ts.expression(), out);
        } else if (s instanceof IfStatement i) {
            collectVarNamesInExpr(i.condition(), out);
            collectVarNames(i.thenBranch(), out);
            if (i.elseBranch() != null) {
                collectVarNames(i.elseBranch(), out);
            }
        } else if (s instanceof VariableDeclaration vd && vd.initializer() != null) {
            collectVarNamesInExpr(vd.initializer(), out);
        }
    }

    /**
     * 递归收集表达式中引用的所有变量名.
     *
     * @param e   待遍历的表达式
     * @param out 输出集合
     */
    private void collectVarNamesInExpr(Expression e, Set<String> out) {
        if (e == null) {
            return;
        }
        if (e instanceof VarExpr v) {
            out.add(v.name());
        } else if (e instanceof BinExpr b) {
            collectVarNamesInExpr(b.left(), out);
            collectVarNamesInExpr(b.right(), out);
        } else if (e instanceof InvocationExpr inv) {
            if (inv.target() != null) {
                collectVarNamesInExpr(inv.target(), out);
            }
            for (Expression a : inv.arguments()) {
                collectVarNamesInExpr(a, out);
            }
        } else if (e instanceof FieldAccessExpr fa && fa.target() != null) {
            collectVarNamesInExpr(fa.target(), out);
        } else if (e instanceof AssignExpr a) {
            collectVarNamesInExpr(a.target(), out);
            collectVarNamesInExpr(a.value(), out);
        }
    }

    /**
     * 判断名称是否为 Java 内置关键字/字面量.
     *
     * @param name 待检查的名称
     * @return 若为内置关键字或字面量返回 {@code true}
     */
    private boolean isBuiltin(String name) {
        return "null".equals(name) || "this".equals(name)
                || "true".equals(name) || "false".equals(name)
                || "super".equals(name);
    }

    /**
     * 根据 Java 类型返回对应的默认值表达式.
     *
     * @param t Java 类型
     * @return 默认值表达式(整型为 0,浮点为 0.0,布尔为 false,对象为 null)
     */
    private Expression defaultVal(JavaType t) {
        if (t == null) {
            return new VarExpr("null");
        }
        return switch (t.kind()) {
            case INT, SHORT, BYTE, CHAR -> new LitExpr(0, JavaType.INT);
            case LONG -> new LitExpr(0L, JavaType.LONG);
            case FLOAT -> new LitExpr(0.0f, JavaType.FLOAT);
            case DOUBLE -> new LitExpr(0.0d, JavaType.DOUBLE);
            case BOOLEAN -> new LitExpr(false, JavaType.BOOLEAN);
            default -> new VarExpr("null");
        };
    }
}
