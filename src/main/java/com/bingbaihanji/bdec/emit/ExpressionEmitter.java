package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 表达式发射器,将 AST 表达式节点输出为 Java 源代码文本.
 * 实现 AstVisitor 接口以支持访问者模式分发.
 */
public class ExpressionEmitter implements AstVisitor<Void, Void> {

    /** 缩进写入器,用于格式化输出源代码 */
    private final IndentWriter w;

    /** 已导入的包集合,用于类型名称简写判断 */
    private final Set<String> importedPackages;

    /** 内部类 friendly 名称映射:内部名称 → 简单名称(如 "TestClass2$1LocalClass" → "LocalClass") */
    private java.util.Map<String, String> innerClassNames = java.util.Map.of();

    /** 语句发射器,用于发射 lambda 块体等需要语句级别输出的表达式 */
    private StatementEmitter stmtEmitter;

    /**
     * 构造表达式发射器,无导入信息.
     *
     * @param w 缩进写入器
     */
    public ExpressionEmitter(IndentWriter w) {
        this(w, List.of());
    }

    /**
     * 构造表达式发射器,根据导入列表初始化包名集合.
     *
     * @param w       缩进写入器
     * @param imports 导入语句列表,如 "java.util.List"
     */
    public ExpressionEmitter(IndentWriter w, List<String> imports) {
        this.w = w;
        this.importedPackages = new HashSet<>();
        for (String imp : imports) {
            // 将 "java.util.List" 形式的导入转换为包名 "java.util"
            int lastDot = imp.lastIndexOf('.');
            if (lastDot >= 0) {
                importedPackages.add(imp.substring(0, lastDot));
            }
        }
    }

    /**
     * 将二元运算符映射为其对应的 Java 符号字符串.
     *
     * @param op 二元运算符枚举
     * @return 运算符符号字符串
     */
    static String opSymbol(BinaryOperator op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case REM -> "%";
            case EQ -> "==";
            case NE -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case AND -> "&&";
            case OR -> "||";
            case BIT_AND -> "&";
            case BIT_OR -> "|";
            case BIT_XOR -> "^";
            case SHL -> "<<";
            case SHR -> ">>";
            case USHR -> ">>>";
            case INSTANCEOF -> "instanceof";
        };
    }

    /**
     * 对字符串进行 Java 字面量转义(处理引号,反斜杠,换行等字符).
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 对单个字符进行 Java 字符字面量转义.
     *
     * @param c 原始字符
     * @return 转义后的字符表示
     */
    private static String escapeChar(char c) {
        return switch (c) {
            case '\'' -> "\\'";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> String.valueOf(c);
        };
    }

    /**
     * 将二元运算符映射为其复合赋值符号前缀(如 + 映射为 += 的 + 部分).
     *
     * @param op 二元运算符枚举
     * @return 复合赋值符号前缀字符串
     */
    private static String compoundSym(BinaryOperator op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case REM -> "%";
            case BIT_AND -> "&";
            case BIT_OR -> "|";
            case BIT_XOR -> "^";
            case SHL -> "<<";
            case SHR -> ">>";
            case USHR -> ">>>";
            default -> "?";
        };
    }

    /** 检查简单类名是否为匿名类引用(如 TestClass2$1, Foo$2LocalClass) */
    private static boolean isAnonymousClassName(String simpleName) {
        int idx = simpleName.lastIndexOf('$');
        if (idx >= 0 && idx + 1 < simpleName.length()) {
            char c = simpleName.charAt(idx + 1);
            return c >= '0' && c <= '9';
        }
        return false;
    }

    /**
     * 设置内部类名称映射,用于将字节码内部名称(如 TestClass2$1LocalClass)
     * 解析为 Java 源码中的友好简单名称(如 LocalClass).
     *
     * @param names 内部名称到简单名称的映射
     */
    public void setInnerClassNames(java.util.Map<String, String> names) {
        this.innerClassNames = names != null ? names : java.util.Map.of();
    }

    /**
     * 设置语句发射器,用于发射 lambda 块体等需要语句级别输出的表达式.
     *
     * @param stmtEmitter 语句发射器
     */
    public void setStmtEmitter(StatementEmitter stmtEmitter) {
        this.stmtEmitter = stmtEmitter;
    }

    /**
     * 解析类型名称为最短有效形式:
     * <ul>
     *   <li>java.lang.* 下的类型 → 简单类名</li>
     *   <li>与已知导入匹配的类型 → 简单类名</li>
     *   <li>其他情况 → 全限定名</li>
     * </ul>
     *
     * @param type Java 类型对象
     * @return 最短有效的类型名称字符串
     */
    String typeName(JavaType type) {
        if (type.kind() == TypeKind.CLASS && type.internalName() != null) {
            String internal = type.internalName();
            String full = internal.replace('/', '.');

            // 检查内部类友好名称映射(如 TestClass2$1LocalClass → LocalClass)
            String rawSimple = internal.substring(internal.lastIndexOf('/') + 1);
            if (innerClassNames.containsKey(rawSimple)) {
                String friendly = innerClassNames.get(rawSimple);
                // 对于命名内部类,直接返回友好简单名称
                if (friendly != null && !friendly.isEmpty()) {
                    // 检查是否有冲突类型(需要包名限定)
                    // 简化处理:对于同包类型,简单名称即可
                    return friendly;
                }
            }

            // 将命名内部类的 $ 转为 .(如 Map$Entry → Map.Entry),
            // 但跳过匿名类(如 TestClass2$1——数字开头的"名称"非法)
            String simple = full.substring(full.lastIndexOf('.') + 1);
            if (!isAnonymousClassName(simple)) {
                full = full.replace('$', '.');
            }
            // java.lang 包下的类型始终使用简单类名
            if (full.startsWith("java.lang.") && full.indexOf('.', 10) < 0) {
                return full.substring(10);
            }
            // 与导入包名匹配的类型使用简单类名
            int lastSlash = internal.lastIndexOf('/');
            if (lastSlash >= 0) {
                String pkg = internal.substring(0, lastSlash).replace('/', '.');
                if (importedPackages.contains(pkg)) {
                    // 返回已转换 $→. 的简单名称
                    if (!isAnonymousClassName(rawSimple)) {
                        return rawSimple.replace('$', '.');
                    }
                    // 匿名类:检查是否有友好名称
                    if (innerClassNames.containsKey(rawSimple)) {
                        String friendly = innerClassNames.get(rawSimple);
                        if (friendly != null && !friendly.isEmpty()) {
                            return friendly;
                        }
                    }
                    return rawSimple;
                }
            }
            return full;
        }
        // 数组类型委托给类型自身的 displayName(递归处理)
        return type.displayName();
    }

    @Override
    public Void visitStatement(Statement stmt, Void context) {
        return null; // 语句由 StatementEmitter 处理
    }

    @Override
    public Void visitExpression(Expression expr, Void context) {
        emit(expr);
        return null;
    }

    /**
     * 根据表达式类型分发到具体的发射方法.
     *
     * @param expr 要发射的表达式节点
     */
    public void emit(Expression expr) {
        switch (expr.kind()) {
            case VARIABLE -> emitVar((VarExpr) expr);
            case LITERAL -> emitLiteral((LitExpr) expr);
            case BINARY -> emitBinary((BinExpr) expr);
            case UNARY -> emitUnary((UnExpr) expr);
            case ASSIGNMENT -> emitAssign((AssignExpr) expr);
            case CONDITIONAL -> emitConditional((CondExpr) expr);
            case INVOCATION -> emitInvocation((InvocationExpr) expr);
            case FIELD_ACCESS -> emitFieldAccess((FieldAccessExpr) expr);
            case CAST -> emitCast((CastExpr) expr);
            case NEW -> emitNew((NewExpr) expr);
            case INSTANCE_OF -> emitInstanceOf((InstanceOfExpr) expr);
            case ARRAY_ACCESS -> emitArrayAccess((ArrayAccessExpr) expr);
            case LAMBDA -> emitLambda((LambdaExpr) expr);
            default -> w.write("/*" + expr.kind() + "*/");
        }
    }

    /**
     * 发射变量引用表达式.
     *
     * @param v 变量表达式节点
     */
    private void emitVar(VarExpr v) {
        w.write(v.name());
    }

    /**
     * 发射字面量表达式(null,字符串,字符,布尔,数值等).
     *
     * @param lit 字面量表达式节点
     */
    private void emitLiteral(LitExpr lit) {
        Object v = lit.value();
        if (v == null) {
            w.write("null");
        } else if (v instanceof String s) {
            w.write("\"").write(escapeString(s)).write("\"");
        } else if (v instanceof Character c) {
            w.write("'").write(escapeChar(c)).write("'");
        } else if (v instanceof Boolean b) {
            w.write(b ? "true" : "false");
        } else if (v instanceof Long l) {
            // long 类型需要后缀 L
            w.write(String.valueOf(l)).write("L");
        } else if (v instanceof Float f) {
            // 处理特殊的浮点数值:NaN 和无穷大
            if (Float.isNaN(f)) {
                w.write("Float.NaN");
            } else if (Float.isInfinite(f)) {
                w.write(f > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY");
            } else {
                // float 类型需要后缀 f
                w.write(String.valueOf(f)).write("f");
            }
        } else if (v instanceof Double d) {
            // 处理特殊的浮点数值:NaN 和无穷大
            if (Double.isNaN(d)) {
                w.write("Double.NaN");
            } else if (Double.isInfinite(d)) {
                w.write(d > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY");
            } else {
                w.write(String.valueOf(d));
            }
        } else {
            w.write(String.valueOf(v));
        }
    }

    /**
     * 发射二元运算表达式,根据优先级决定是否添加括号.
     *
     * @param bin 二元运算表达式节点
     */
    private void emitBinary(BinExpr bin) {
        BinaryOperator op = bin.operator();
        Expression left = bin.left(), right = bin.right();
        emitWithParens(left, bin.precedence());
        w.write(" ").write(opSymbol(op)).write(" ");
        emitWithParens(right, bin.precedence());
    }

    /**
     * 发射一元运算表达式(取负,取反,自增,自减等).
     *
     * @param un 一元运算表达式节点
     */
    private void emitUnary(UnExpr un) {
        UnaryOperator op = un.operator();
        String sym = switch (op) {
            case NEG -> "-";
            case NOT -> "!";
            case COMPLEMENT -> "~";
            case PRE_INC -> "++";
            case PRE_DEC -> "--";
            // 后缀运算符在操作数之后输出
            case POST_INC, POST_DEC -> "";
        };
        w.write(sym);
        // 使用 emitWithParens 而非 emit,确保低优先级操作数
        // (如 instanceof 优先级 8)在 ! 前缀(优先级 13)后正确加括号
        emitWithParens(un.operand(), un.precedence());
        // 输出后缀运算符
        if (op == UnaryOperator.POST_INC) {
            w.write("++");
        } else if (op == UnaryOperator.POST_DEC) {
            w.write("--");
        }
    }

    /**
     * 发射赋值表达式(普通赋值和复合赋值).
     *
     * @param a 赋值表达式节点
     */
    private void emitAssign(AssignExpr a) {
        emit(a.target());
        if (a.compoundOp() != null) {
            // 复合赋值如 +=,-=,*= 等
            w.space().write(compoundSym(a.compoundOp())).write("= ");
        } else {
            w.write(" = ");
        }
        emit(a.value());
    }

    /**
     * 发射三元条件表达式(condition ? trueExpr : falseExpr).
     *
     * @param c 条件表达式节点
     */
    private void emitConditional(CondExpr c) {
        emitWithParens(c.condition(), c.precedence());
        w.write(" ? ");
        emit(c.trueExpr());
        w.write(" : ");
        emit(c.falseExpr());
    }

    /**
     * 发射方法调用表达式.
     *
     * @param inv 方法调用表达式节点
     */
    private void emitInvocation(InvocationExpr inv) {
        if (inv.target() != null) {
            emit(inv.target());
            w.write(".");
        }
        w.write(inv.methodName()).write("(");
        List<Expression> args = inv.arguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                w.write(", ");
            }
            emit(args.get(i));
        }
        w.write(")");
    }

    /**
     * 发射字段访问表达式.
     *
     * @param fa 字段访问表达式节点
     */
    private void emitFieldAccess(FieldAccessExpr fa) {
        if (fa.target() != null) {
            emit(fa.target());
            w.write(".");
        }
        w.write(fa.fieldName());
    }

    /**
     * 发射 instanceof 类型检查表达式.
     *
     * @param io instanceof 表达式节点
     */
    private void emitInstanceOf(InstanceOfExpr io) {
        if (io.operand() != null) {
            emitWithParens(io.operand(), io.precedence());
        } else {
            w.write("obj");
        }
        w.write(" instanceof ");
        w.write(typeName(io.targetType()));
    }

    /**
     * 发射 Lambda 表达式或方法引用.
     *
     * @param lambda Lambda 表达式节点
     */
    private void emitLambda(LambdaExpr lambda) {
        if (lambda.isMethodRef()) {
            // 方法引用(如 String::valueOf)
            w.write(lambda.methodRefOwner());
            w.write("::");
            w.write(lambda.methodRefName());
            return;
        }
        // Lambda 表达式体
        w.write("(");
        List<LambdaExpr.Param> params = lambda.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                w.write(", ");
            }
            LambdaExpr.Param p = params.get(i);
            if (p.type() != null && p.type().kind() != com.bingbaihanji.bdec.type.TypeKind.CLASS) {
                w.write(typeName(p.type()));
                w.write(" ");
            }
            w.write(p.name());
        }
        w.write(") -> ");
        if (lambda.isExpressionBody() && lambda.bodyExpr() != null) {
            // 表达式体 lambda
            emit(lambda.bodyExpr());
        } else if (lambda.bodyBlock() != null) {
            // 块体 lambda — 输出实际代码块内容
            BlockStatement body = lambda.bodyBlock();
            List<Statement> stmts = body.statements();
            if (stmts.isEmpty()) {
                w.write("{}");
            } else if (stmts.size() == 1) {
                // 单条语句:内联为简洁的 { stmt } 形式
                w.write("{ ");
                if (stmtEmitter != null) {
                    stmtEmitter.emit(stmts.get(0));
                } else {
                    w.write("/* no emitter */");
                }
                w.write(" }");
            } else {
                // 多条语句:展开为多行块
                w.write("{").newLine();
                w.indent();
                if (stmtEmitter != null) {
                    for (Statement stmt : stmts) {
                        stmtEmitter.emit(stmt);
                    }
                } else {
                    w.write("/* no emitter */");
                    w.newLine();
                }
                w.dedent();
                w.write("}");
            }
        } else {
            w.write("{}");
        }
    }

    /**
     * 发射数组访问表达式.
     *
     * @param aa 数组访问表达式节点
     */
    private void emitArrayAccess(ArrayAccessExpr aa) {
        if (aa.array() != null) {
            emitWithParens(aa.array(), aa.precedence());
        } else {
            w.write("arr");
        }
        w.write("[");
        if (aa.index() != null) {
            emit(aa.index());
        }
        w.write("]");
    }

    /**
     * 发射类型转换表达式(强制转型).
     *
     * @param cast 类型转换表达式节点
     */
    private void emitCast(CastExpr cast) {
        w.write("(").write(typeName(cast.targetType())).write(") ");
        emitWithParens(cast.operand(), cast.precedence());
    }

    /**
     * 发射 new 实例化表达式(创建对象或数组).
     *
     * @param n new 表达式节点
     */
    private void emitNew(NewExpr n) {
        w.write("new ").write(typeName(n.instantiatedType()));
        if (!n.constructorArgs().isEmpty()) {
            // 构造器参数调用
            w.write("(");
            List<Expression> args = n.constructorArgs();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    w.write(", ");
                }
                emit(args.get(i));
            }
            w.write(")");
        } else if (!n.dimensions().isEmpty()) {
            // 多维数组创建
            w.write("[");
            for (int i = 0; i < n.dimensions().size(); i++) {
                if (i > 0) {
                    w.write("][");
                }
                emit(n.dimensions().get(i));
            }
            w.write("]");
        } else {
            w.write("()");
        }
    }

    /**
     * 根据运算符优先级决定是否为子表达式添加括号后发射.
     *
     * @param expr             子表达式
     * @param parentPrecedence 父表达式的优先级
     */
    private void emitWithParens(Expression expr, int parentPrecedence) {
        if (expr.precedence() < parentPrecedence) {
            w.write("(");
            emit(expr);
            w.write(")");
        } else {
            emit(expr);
        }
    }
}
