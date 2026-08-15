package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.ast.expr.*;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.util.TypeNameRenderer;

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

    /** 当前编译单元包名(默认包为 ""),用于同包类型简写判断 */
    private final String currentPackage;

    /** 已导入的全限定名集合(精确匹配),用于类型名称简写判断 */
    private final Set<String> importedFqns;

    /** 内部类 friendly 名称映射:内部名称 → 简单名称(如 "TestClass2$1LocalClass" → "LocalClass") */
    private java.util.Map<String, String> innerClassNames = java.util.Map.of();

    /** 语句发射器,用于发射 lambda 块体等需要语句级别输出的表达式 */
    private StatementEmitter stmtEmitter;

    /**
     * 构造表达式发射器,无导入信息(默认包).
     *
     * @param w 缩进写入器
     */
    public ExpressionEmitter(IndentWriter w) {
        this(w, "", List.of());
    }

    /**
     * 构造表达式发射器,根据导入列表初始化全限定名集合(默认包).
     *
     * @param w       缩进写入器
     * @param imports 导入语句列表,如 "java.util.List"
     */
    public ExpressionEmitter(IndentWriter w, List<String> imports) {
        this(w, "", imports);
    }

    /**
     * 构造表达式发射器,携带当前包名与导入列表.
     *
     * @param w              缩进写入器
     * @param currentPackage 当前编译单元包名(默认包为 "")
     * @param imports        导入语句列表,如 "java.util.List"
     */
    public ExpressionEmitter(IndentWriter w, String currentPackage, List<String> imports) {
        this.w = w;
        this.currentPackage = currentPackage != null ? currentPackage : "";
        this.importedFqns = new HashSet<>(imports);
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

    /** 在类型路径末尾追加一个元素(不可变列表). */
    private static java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> appendPath(
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> path,
            int kind, int index) {
        java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> next
                = new java.util.ArrayList<>(path);
        next.add(new com.bingbaihanji.bdec.bytecode.model.TypePathElement(kind, index));
        return next;
    }

    /** 数组类型的元素类型(剥离最外层一维). */
    private static JavaType elementOfArray(JavaType arrayType) {
        return JavaType.elementOf(arrayType);
    }

    /**
     * 数组类型的总维度数(嵌套/累积表示统一,与 {@link #arrayBaseName} 括号计数一致).
     * <p>anewarray 仅给出最内层维度大小,类型却携带全部括号,
     * 发射 {@code new T[d][]} 时须按此总数补齐未显式指定的空维度.</p>
     */
    private int totalArrayDimensions(JavaType type) {
        if (type == null || type.kind() != TypeKind.ARRAY) {
            return 0;
        }
        if (type.element() != null && type.element().kind() == TypeKind.ARRAY) {
            int remaining = Math.max(1,
                    type.arrayDimensions() - type.element().arrayDimensions());
            return remaining + totalArrayDimensions(type.element());
        }
        return type.arrayDimensions();
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
        String baseName = typeBaseName(type);
        // 附加泛型类型参数(如 BiFunction<Integer, Integer, Integer>).
        // WILDCARD 的边界已包含在名称中(? extends Number),不重复追加.
        if (!type.typeArguments().isEmpty() && type.kind() != TypeKind.WILDCARD) {
            StringBuilder sb = new StringBuilder(baseName);
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(typeName(type.typeArguments().get(i)));
            }
            sb.append('>');
            return sb.toString();
        }
        return baseName;
    }

    /**
     * 渲染带 JSR-308 类型注解的类型名.
     *
     * <p>注解按类型路径定位在类型树中的准确位置(与 javac 的编码一致):</p>
     * <ul>
     *   <li>路径为空 → 注解应用于该类型本身(数组场景输出为 {@code String @A []})</li>
     *   <li>{@code [TYPE_ARGUMENT(i)]} → 泛型参数 i(如 {@code List<@A String>})</li>
     *   <li>{@code [ARRAY]*} → 沿维度深入(元素类型注解输出为 {@code @A String[]})</li>
     * </ul>
     *
     * @param type 类型对象
     * @param anns 类型路径 → 渲染后注解行列表的映射
     * @return 含类型注解的类型名
     */
    String typeNameAnnotated(JavaType type,
                             java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                                     java.util.List<String>> anns) {
        if (anns == null || anns.isEmpty()) {
            return typeName(type);
        }
        return renderAnnotatedType(type, java.util.List.of(), anns);
    }

    /** 递归渲染类型树,沿途在对应路径处插入注解. */
    private String renderAnnotatedType(
            JavaType type,
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> path,
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    java.util.List<String>> anns) {
        // 数组:元素类型递归渲染(路径沿每个维度深入).
        // 各层数组本身的注解输出在对应 "[]" 之前,与 javac 的 type_path 编码一致:
        //   @A String[]  → 元素类型注解,路径 [ARRAY]
        //   String @A [] → 数组本身注解,路径为空
        //   String @A [] @B [] → 外层数组(空路径)在前一个 "[]" 前,
        //                         元素数组([ARRAY])在后一个 "[]" 前
        if (type.kind() == TypeKind.ARRAY) {
            StringBuilder sb = new StringBuilder();
            // TypeResolver 维度累积形态(如 [[String → ARRAY(ARRAY(String,1),2)):
            // 元素递归已含内层括号,外层仅补差值,否则括号翻倍(与 arrayBaseName 同式).
            JavaType elem = elementOfArray(type);
            int outerDims = (elem != null && elem.kind() == TypeKind.ARRAY)
                    ? Math.max(1, type.arrayDimensions() - elem.arrayDimensions())
                    : type.arrayDimensions();
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> elemPath = path;
            for (int d = 0; d < type.arrayDimensions(); d++) {
                elemPath = appendPath(elemPath,
                        com.bingbaihanji.bdec.bytecode.model.TypePathElement.KIND_ARRAY, d);
            }
            sb.append(renderAnnotatedType(elem, elemPath, anns));
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> cur = path;
            for (int d = 0; d < outerDims; d++) {
                boolean annotated = false;
                for (String a : anns.getOrDefault(cur, java.util.List.of())) {
                    sb.append(' ').append(a);
                    annotated = true;
                }
                // 有注解时保留空格(如 "String @NonNull []"),无注解时紧凑输出 "[]"
                sb.append(annotated ? " []" : "[]");
                cur = appendPath(cur,
                        com.bingbaihanji.bdec.bytecode.model.TypePathElement.KIND_ARRAY, d);
            }
            return sb.toString();
        }
        return renderAnnotatedBase(type, path, anns);
    }

    /**
     * 渲染非数组类型的注解化名称:路径处注解 + 基础名 + 泛型参数递归.
     * 供 {@link #renderAnnotatedType} 与 new 表达式发射共用.
     */
    private String renderAnnotatedBase(
            JavaType type,
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> path,
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    java.util.List<String>> anns) {
        StringBuilder sb = new StringBuilder();
        for (String a : anns.getOrDefault(path, java.util.List.of())) {
            sb.append(a).append(' ');
        }
        sb.append(typeBaseName(type));
        // 泛型参数递归渲染(类型路径深入 TYPE_ARGUMENT(i));
        // WILDCARD 的边界已包含在基础名中,不重复展开.
        if (!type.typeArguments().isEmpty() && type.kind() != TypeKind.WILDCARD) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> argPath
                        = appendPath(path,
                        com.bingbaihanji.bdec.bytecode.model.TypePathElement.KIND_TYPE_ARGUMENT, i);
                sb.append(renderAnnotatedType(type.typeArguments().get(i), argPath, anns));
            }
            sb.append('>');
        }
        return sb.toString();
    }

    /** path 处的注解渲染行列表(无则空). */
    private java.util.List<String> annotationsAt(
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> path,
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    java.util.List<String>> anns) {
        return anns.getOrDefault(path, java.util.List.of());
    }

    /** 渲染不带泛型参数的基础类型名 */
    private String typeBaseName(JavaType type) {
        if (type.kind() == TypeKind.CLASS && type.internalName() != null) {
            // CLASS 短名解析统一委托 TypeNameRenderer(单一事实源),
            // 与 TypeText 收集模式共享 java.lang/同包/匿名类/$→. 规则
            return TypeNameRenderer.className(type, currentPackage,
                    innerClassNames, importedFqns);
        }
        if (type.kind() == TypeKind.ARRAY) {
            return arrayBaseName(type);
        }
        if (type.kind() == TypeKind.WILDCARD) {
            return wildcardTypeName(type);
        }
        return type.displayName();
    }

    /** 渲染数组类型的基础名:元素 import 感知 + 维度括号(与 displayName 括号计数一致). */
    private String arrayBaseName(JavaType type) {
        JavaType elem = elementOfArray(type);
        String elemName = typeName(elem);
        if (type.element() != null && type.element().kind() == TypeKind.ARRAY) {
            // 嵌套数组(元素本身是数组):元素名已含内层括号,仅补外层差值
            int remaining = Math.max(1,
                    type.arrayDimensions() - type.element().arrayDimensions());
            return elemName + "[]".repeat(remaining);
        }
        return elemName + "[]".repeat(Math.max(0, type.arrayDimensions()));
    }

    /** 渲染通配符类型名:边界 import 感知(? extends X / ? super Y). */
    private String wildcardTypeName(JavaType type) {
        String bound = !type.typeArguments().isEmpty()
                ? typeName(type.typeArguments().getFirst()) : null;
        if (type.internalName() != null && type.internalName().startsWith("? super ")) {
            return "? super " + (bound != null ? bound : "Object");
        }
        if (type.internalName() != null && type.internalName().startsWith("? extends ")) {
            return "? extends " + (bound != null ? bound : "Object");
        }
        return "?";
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
            if (s.contains("\n") && s.lines().count() >= 3) {
                // 多行字符串 → 文本块(TextBlockRewriter 标记的转换目标)
                emitTextBlock(s);
            } else {
                w.write("\"").write(escapeString(s)).write("\"");
            }
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
        } else if (v instanceof Number n && lit.type() != null
                && (lit.type().kind() == com.bingbaihanji.bdec.type.TypeKind.BYTE
                || lit.type().kind() == com.bingbaihanji.bdec.type.TypeKind.SHORT)) {
            // byte/short 无字面量后缀:常量实参传给 byte/short 参数时
            //(如 calc((byte)10))须显式强转,否则重编译报"有损转换".
            w.write(lit.type().kind() == com.bingbaihanji.bdec.type.TypeKind.BYTE
                    ? "(byte) " : "(short) ");
            w.write(String.valueOf(n));
        } else {
            w.write(String.valueOf(v));
        }
    }

    /**
     * 发射文本块字符串.
     *
     * <p>输出格式与 javac 一致:开始定界符后换行,内容行缩进一级,
     * 结束定界符独占一行.伴随缩进由 javac 按结束定界符的列位置剥离,
     * 内容即为原始字符串(含结尾换行).</p>
     */
    private void emitTextBlock(String s) {
        w.write("\"\"\"").newLine();
        w.indent();
        String[] lines = s.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 最后一段为空(结尾换行)——不输出空内容行
            if (i == lines.length - 1 && line.isEmpty()) {
                break;
            }
            w.write(escapeTextBlockLine(line)).newLine();
        }
        w.dedent();
        w.write("\"\"\"");
    }

    /** 文本块行转义:反斜杠与定界符序列 */
    private String escapeTextBlockLine(String line) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '"' && i + 2 < line.length()
                    && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
                sb.append("\\\"\"\"");
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
            // 使用 emitWithParens 确保低优先级目标(如 CastExpr)正确加括号.
            // 例如 ((Integer)var2).intValue() 而非 (Integer)var2.intValue().
            emitWithParens(inv.target(), inv.precedence());
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
            // 使用 emitWithParens 确保低优先级目标正确加括号
            emitWithParens(fa.target(), fa.precedence());
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
        w.write(typeNameAnnotated(io.targetType(), io.typeAnnotations()));
    }

    /**
     * 发射 Lambda 表达式或方法引用.
     *
     * @param lambda Lambda 表达式节点
     */
    private void emitLambda(LambdaExpr lambda) {
        if (lambda.isMethodRef()) {
            // 方法引用(如 String::valueOf)
            List<String> recvAnns = lambda.methodRefReceiverAnnotations();
            if (recvAnns != null) {
                for (String ann : recvAnns) {
                    w.write(ann);
                    w.write(" ");
                }
            }
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
            if (p.type() != null && p.type().kind() != TypeKind.CLASS
                    && p.type().kind() != TypeKind.TYPE_VARIABLE) {
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
        w.write("(").write(typeNameAnnotated(cast.targetType(), cast.typeAnnotations())).write(") ");
        emitWithParens(cast.operand(), cast.precedence());
    }

    /**
     * 发射 new 实例化表达式(创建对象或数组).
     *
     * @param n new 表达式节点
     */
    private void emitNew(NewExpr n) {
        JavaType type = n.instantiatedType();
        var anns = n.typeAnnotations();
        if (anns.isEmpty()) {
            // 非数组且带泛型参数的类型用 typeName 展开泛型(如 ArrayList<String>),
            // 与注解路径 renderAnnotatedBase 的泛型递归保持一致(未来兼容:
            // 字节码层 new 类型天然擦除,当前无路径携带 typeArguments);
            // 无泛型/数组类型保持原 typeBaseName 行为不变.
            String name = (type.arrayDimensions() == 0 && !type.typeArguments().isEmpty())
                    ? typeName(type)
                    : typeBaseName(type);
            if (type.arrayDimensions() > 0) {
                // 去除数组后缀: "String[]" → "String"(维度由下方 dims 分支自行发射)
                int bracketIdx = name.indexOf('[');
                if (bracketIdx >= 0) {
                    name = name.substring(0, bracketIdx);
                }
            }
            w.write("new ").write(name);
        } else if (type.arrayDimensions() > 0) {
            // JSR-308 注解数组创建:new @A String[3](元素注解,路径 [ARRAY])
            // 或 new String @A [3](数组本身注解,路径为空).
            // 维度连同注解在此发射,直接返回避免走下方 dims 分支重复输出.
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> elemPath
                    = java.util.List.of();
            for (int d = 0; d < type.arrayDimensions(); d++) {
                elemPath = appendPath(elemPath,
                        com.bingbaihanji.bdec.bytecode.model.TypePathElement.KIND_ARRAY, d);
            }
            w.write("new ").write(renderAnnotatedBase(elementOfArray(type), elemPath, anns));
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement> cur
                    = java.util.List.of();
            for (int d = 0; d < n.dimensions().size(); d++) {
                java.util.List<String> dimAnns = annotationsAt(cur, anns);
                for (String a : dimAnns) {
                    w.write(' ').write(a);
                }
                // 有注解时保留空格(与 renderAnnotatedType 的 " []" 惯例一致)
                w.write(dimAnns.isEmpty() ? "[" : " [");
                emit(n.dimensions().get(d));
                w.write("]");
                cur = appendPath(cur,
                        com.bingbaihanji.bdec.bytecode.model.TypePathElement.KIND_ARRAY, d);
            }
            return;
        } else {
            // JSR-308 注解对象创建:new @A ArrayList<>()(含泛型参数递归)
            w.write("new ").write(renderAnnotatedBase(type, java.util.List.of(), anns));
        }
        // 菱形推断:类型实参可被构造器实参或目标类型推断时发射 new ArrayList<>(...).
        // 匿名类不可用菱形(Java 语法限制).
        if (n.diamond() && !n.isAnonymousClass()) {
            w.write("<>");
        }
        if (!n.constructorArgs().isEmpty()) {
            // 构造器参数调用(如 new ArrayList(args))
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
            // 数组创建:new String[3] 或 new int[3][5]
            for (Expression dim : n.dimensions()) {
                w.write("[");
                emit(dim);
                w.write("]");
            }
            // 未显式给出的剩余维度补空括号(如 anewarray [I 产 new int[2][])
            int remaining = totalArrayDimensions(type) - n.dimensions().size();
            for (int d = 0; d < remaining; d++) {
                w.write("[]");
            }
        } else if (!n.arrayInitializer().isEmpty()) {
            // 数组初始化器:new String[]{"a", "b"}
            w.write("[]{");
            List<Expression> elems = n.arrayInitializer();
            for (int i = 0; i < elems.size(); i++) {
                if (i > 0) {
                    w.write(", ");
                }
                emit(elems.get(i));
            }
            w.write("}");
        } else {
            w.write("()");
        }
        // 匿名类体:new Type(args) { <成员> }
        if (n.isAnonymousClass() && stmtEmitter != null) {
            w.space().write("{").newLine();
            w.indent();
            for (com.bingbaihanji.bdec.ast.AstNode member : n.anonymousBody()) {
                if (member instanceof com.bingbaihanji.bdec.ast.stmt.Statement st) {
                    stmtEmitter.emit(st);
                }
            }
            w.dedent();
            w.write("}");
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
