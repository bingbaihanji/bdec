package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AnnotationRenderer;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.expr.LambdaExpr;
import com.bingbaihanji.bdec.ast.expr.VarExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.IfStatement;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.ReturnStatement;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.ast.stmt.VariableDeclaration;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 方法引用重写器,检测解析为方法引用的 {@code invokedynamic} 模式,
 * 将其转换为 Java 的 {@code ::} 语法.
 *
 * <p>支持四种方法引用类型:
 * <pre>
 *   静态:      ClassName::staticMethod      (INVOKESTATIC)
 *   绑定:      expr::instanceMethod          (INVOKEVIRTUAL,接收者已捕获)
 *   未绑定:    ClassName::instanceMethod     (INVOKEVIRTUAL,接收者为第 1 个参数)
 *   构造器:    ClassName::new                (NEW + INVOKESPECIAL 初始化)
 * </pre>
 *
 * <p>额外恢复方法引用上的 JSR-308 类型注解,数据仅存在于 Code 属性内的
 * RuntimeVisibleTypeAnnotations(BootstrapMethods 不携带注解),经 invokedynamic →
 * 常量池 → BootstrapMethods → 实现方法句柄与实例化方法类型还原:
 * <ul>
 *   <li>显式类型实参上的注解(目标 {@code 0x4A} CONSTRUCTOR_REFERENCE_TYPE_ARGUMENT /
 *       {@code 0x4B} METHOD_REFERENCE_TYPE_ARGUMENT,
 *       target_info = [invokedynamic 偏移, 类型实参下标]),如 {@code C::<@A String>id}</li>
 *   <li>接收者(被引用类型)上的注解(目标 {@code 0x45} CONSTRUCTOR_REFERENCE /
 *       {@code 0x46} METHOD_REFERENCE,target_info = [invokedynamic 偏移]),
 *       如 {@code @A C::id},{@code @A C::new}</li>
 * </ul></p>
 *
 * <p>设计参考 CFR 的 lambda 处理和 Vineflower 的 {@code LambdaProcessor}.
 */
public class MethodRefRewriter implements RewriteRule {

    /** JVMS 4.7.20.1:构造器引用类型实参上的类型注解 */
    private static final int TARGET_CTOR_REF_TYPE_ARGUMENT = 0x4A;

    /** JVMS 4.7.20.1:方法引用类型实参上的类型注解 */
    private static final int TARGET_METHOD_REF_TYPE_ARGUMENT = 0x4B;

    /** JVMS 4.7.20.1:构造器引用接收者(被引用类型)上的类型注解 */
    private static final int TARGET_CTOR_REF_RECEIVER = 0x45;

    /** JVMS 4.7.20.1:方法引用接收者(被引用类型)上的类型注解 */
    private static final int TARGET_METHOD_REF_RECEIVER = 0x46;

    /** 在 class 文件模型中按名称查找方法模型(重载时取首个匹配,尽力而为) */
    private static MethodModel findMethodModel(DecompileContext context, String name) {
        if (name == null) {
            // AST 中构造器等方法声明的名称为 null,无对应注解可查
            return null;
        }
        ClassFileModel classFile = context.classFile();
        if (classFile == null || classFile.methods() == null) {
            return null;
        }
        for (MethodModel m : classFile.methods()) {
            if (name.equals(m.name())) {
                return m;
            }
        }
        return null;
    }

    @Override
    public String name() {return "method-ref";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<BootstrapMethodEntry> bootstrapMethods = context.bootstrapMethods();
        if (bootstrapMethods == null || bootstrapMethods.isEmpty()) {
            return unit;
        }

        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /** 递归重写类型声明中的每个方法体 */
    private TypeDeclaration rewriteType(TypeDeclaration td, DecompileContext context) {
        List<AstNode> members = new ArrayList<>();
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md) {
                MethodModel mm = findMethodModel(context, md.name());
                MethodRefAnnotator annotator = mm != null
                        ? MethodRefAnnotator.forMethod(mm, context) : null;
                members.add(withBody(md, md.body() != null
                        ? rewriteStatement(md.body(), annotator) : null));
            } else {
                members.add(m);
            }
        }
        return withMembers(td, members);
    }

    /** 递归重写语句,识别方法引用调用 */
    private Statement rewriteStatement(Statement s, MethodRefAnnotator annotator) {
        if (s instanceof BlockStatement bs) {
            return new BlockStatement(bs.statements().stream()
                    .map(st -> rewriteStatement(st, annotator)).toList());
        }
        if (s instanceof ExpressionStatement es) {
            Expression rewritten = rewriteExpr(es.expression(), annotator);
            return new ExpressionStatement(rewritten);
        }
        if (s instanceof ReturnStatement rs) {
            return new ReturnStatement(rs.value() != null
                    ? rewriteExpr(rs.value(), annotator) : null);
        }
        if (s instanceof IfStatement i) {
            return new IfStatement(rewriteExpr(i.condition(), annotator),
                    rewriteStatement(i.thenBranch(), annotator),
                    i.elseBranch() != null ? rewriteStatement(i.elseBranch(), annotator) : null);
        }
        if (s instanceof VariableDeclaration vd) {
            Expression init = vd.initializer() != null
                    ? rewriteExpr(vd.initializer(), annotator) : null;
            return new VariableDeclaration(vd.type(), vd.name(), init, vd.typeAnnotations());
        }
        return s;
    }

    /** 重写表达式,检测方法引用模式并转换为 :: 语法 */
    private Expression rewriteExpr(Expression e, MethodRefAnnotator annotator) {
        if (e instanceof InvocationExpr inv) {
            // 先递归处理子表达式
            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : inv.arguments()) {
                newArgs.add(rewriteExpr(arg, annotator));
            }
            Expression newTarget = inv.target() != null
                    ? rewriteExpr(inv.target(), annotator) : null;

            // 检测方法引用模式
            String name = inv.methodName();
            if (name != null && newTarget == null && !newArgs.isEmpty()) {
                // 可能为方法引用:invokedynamic 带静态参数
                Expression methodRef = tryConvertMethodRef(name, newArgs);
                if (methodRef != null) {
                    return methodRef;
                }
            }

            return new InvocationExpr(newTarget, name, newArgs, inv.returnType());
        }
        // 方法引用 LambdaExpr:附着显式类型实参上的类型注解(C::<@A String>id)
        if (annotator != null && e instanceof LambdaExpr lambda && lambda.isMethodRef()) {
            return annotator.apply(lambda);
        }
        return e;
    }

    /**
     * 尝试将 invokedynamic 调用转换为 {@code ::} 方法引用.
     * 模式:invokedynamic 的名称包含 "$$" 或以已知的 lambda/SAM 前缀开头.
     */
    private Expression tryConvertMethodRef(String name, List<Expression> args) {
        // 模式一:new ClassName() 构造器引用
        if (name.startsWith("new") && args.size() == 1
                && args.get(0) instanceof VarExpr vx) {
            return new VarExpr(vx.name() + "::new");
        }

        // 模式二:静态方法引用 Class::method
        if (name.contains("::")) {
            return new VarExpr(name); // 已是格式化后的结果
        }

        // 模式三:来自 indy 的一般方法引用模式
        // methodName$hash 或 lambda$method$N → 提取
        if (name.contains("$") && args.size() >= 1) {
            // 尝试格式化为 Class::method
            String clean = name.replace("lambda$", "").replace("$", "::");
            if (clean.contains("::")) {
                return new VarExpr(clean);
            }
        }

        return null;
    }

    // ── 方法引用类型实参注解(0x4A/0x4B)恢复 ──

    /**
     * 将方法模型中的 0x4A/0x4B 类型实参与 0x45/0x46 接收者类型注解附着到
     * AST 中的方法引用节点.
     *
     * <p>匹配规则:注解条目按字节码偏移升序,AST 方法引用按遍历(源码)顺序,
     * 逐一按 (所有者, 方法名) 配对消费——字节码顺序与源码顺序在直线代码中一致.
     * 同型多次引用的站点无法再进一步区分(字节码中仅偏移不同),取首个匹配.</p>
     */
    private static final class MethodRefAnnotator {

        private final DecompileContext context;

        private final MethodModel method;

        private final List<Entry> entries;

        private int cursor;

        private MethodRefAnnotator(DecompileContext context, MethodModel method, List<Entry> entries) {
            this.context = context;
            this.method = method;
            this.entries = entries;
        }

        /** 收集方法上的 0x4A/0x4B 类型实参与 0x45/0x46 接收者注解条目;无则返回 null */
        static MethodRefAnnotator forMethod(MethodModel method, DecompileContext context) {
            List<Entry> entries = new ArrayList<>();
            if (method.typeAnnotations() != null) {
                for (TypeAnnotationEntry ta : method.typeAnnotations()) {
                    if (ta.targetType() == TARGET_METHOD_REF_TYPE_ARGUMENT
                            || ta.targetType() == TARGET_CTOR_REF_TYPE_ARGUMENT) {
                        int[] ti = ta.targetInfo();
                        if (ti == null || ti.length < 2) {
                            continue;
                        }
                        String rendered = AnnotationRenderer.render(ta.annotation(),
                                n -> n.substring(n.lastIndexOf('/') + 1));
                        entries.add(new Entry(ti[0], ta.targetType(), ti[1], rendered));
                    } else if (ta.targetType() == TARGET_METHOD_REF_RECEIVER
                            || ta.targetType() == TARGET_CTOR_REF_RECEIVER) {
                        // target_info = [offset];type_path 恒为空
                        //(javac 拒绝 Outer.@A Inner::id 等 INNER_TYPE 位置)
                        int[] ti = ta.targetInfo();
                        if (ti == null || ti.length < 1) {
                            continue;
                        }
                        String rendered = AnnotationRenderer.render(ta.annotation(),
                                n -> n.substring(n.lastIndexOf('/') + 1));
                        entries.add(new Entry(ti[0], ta.targetType(), -1, rendered));
                    }
                }
            }
            if (entries.isEmpty()) {
                return null;
            }
            entries.sort(Comparator.comparingInt(Entry::offset));
            return new MethodRefAnnotator(context, method, entries);
        }

        private static void fillImpl(RefInfo info, ConstantPoolEntry[] pool,
                                     int classIndex, int nameAndTypeIndex) {
            info.owner = ConstantPoolParser.className(pool, classIndex);
            if (pool[nameAndTypeIndex] instanceof ConstantPoolEntry.CpNameAndType nt) {
                info.name = ConstantPoolParser.utf8(pool, nt.nameIndex());
                info.implDescriptor = ConstantPoolParser.utf8(pool, nt.descriptorIndex());
            }
        }

        /** 节点 (所有者, 方法名) 与实现方法句柄是否一致 */
        private static boolean matches(LambdaExpr node, RefInfo info) {
            if (info.owner == null || info.name == null) {
                return false;
            }
            if (!simpleName(info.owner).equals(node.methodRefOwner())) {
                return false;
            }
            String implName = "<init>".equals(info.name) ? "new" : info.name;
            return implName.equals(node.methodRefName());
        }

        /** 提取泛型签名中的类型形参声明名列表(按声明顺序) */
        private static List<String> formalTypeParameterNames(String signature) {
            List<String> names = new ArrayList<>();
            if (signature == null || signature.isEmpty() || signature.charAt(0) != '<') {
                return names;
            }
            int depth = 0;
            int start = 1;
            for (int i = 1; i < signature.length(); i++) {
                char c = signature.charAt(i);
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (c == ':' && depth == 0) {
                    names.add(signature.substring(start, i));
                } else if (c == ';' && depth == 0) {
                    start = i + 1;
                }
            }
            return names;
        }

        /** 解析方法描述符(可含泛型签名)为参数+返回的源码文本列表 */
        private static List<String> methodTypeParts(String descriptor) {
            List<String> parts = new ArrayList<>();
            int i = 1; // 跳过 '('
            while (i < descriptor.length() && descriptor.charAt(i) != ')') {
                TypeText r = renderFieldType(descriptor, i);
                parts.add(r.text());
                i = r.next();
            }
            i++; // 跳过 ')'
            TypeText ret = renderFieldType(descriptor, i);
            parts.add(ret.text());
            return parts;
        }

        /** 渲染单个字段类型描述符(含泛型/通配符/类型变量)为源码文本 */
        private static TypeText renderFieldType(String desc, int i) {
            char c = desc.charAt(i);
            return switch (c) {
                case 'V' -> new TypeText("void", i + 1);
                case 'Z' -> new TypeText("boolean", i + 1);
                case 'B' -> new TypeText("byte", i + 1);
                case 'S' -> new TypeText("short", i + 1);
                case 'C' -> new TypeText("char", i + 1);
                case 'I' -> new TypeText("int", i + 1);
                case 'J' -> new TypeText("long", i + 1);
                case 'F' -> new TypeText("float", i + 1);
                case 'D' -> new TypeText("double", i + 1);
                case '[' -> {
                    TypeText elem = renderFieldType(desc, i + 1);
                    yield new TypeText(elem.text() + "[]", elem.next());
                }
                case 'L' -> {
                    int semi = desc.indexOf(';', i);
                    String raw = desc.substring(i + 1, semi);
                    int lt = raw.indexOf('<');
                    if (lt > 0) {
                        String name = simpleName(raw.substring(0, lt));
                        int gt = raw.lastIndexOf('>');
                        String argsText = raw.substring(lt + 1, gt);
                        List<String> args = new ArrayList<>();
                        int p = 0;
                        while (p < argsText.length()) {
                            TypeText ar = renderFieldType(argsText, p);
                            args.add(ar.text());
                            p = ar.next();
                        }
                        yield new TypeText(name + "<" + String.join(", ", args) + ">", semi + 1);
                    }
                    yield new TypeText(simpleName(raw), semi + 1);
                }
                case 'T' -> {
                    int semi = desc.indexOf(';', i);
                    yield new TypeText(desc.substring(i + 1, semi), semi + 1);
                }
                case '+', '-' -> {
                    TypeText inner = renderFieldType(desc, i + 1);
                    yield new TypeText((c == '+' ? "? extends " : "? super ") + inner.text(),
                            inner.next());
                }
                case '*' -> new TypeText("?", i + 1);
                default -> new TypeText(String.valueOf(c), i + 1);
            };
        }

        /** 内部名 → 简单名("java/lang/String" → "String","C$I" → "C.I") */
        private static String simpleName(String internalName) {
            if (internalName == null) {
                return null;
            }
            int slash = internalName.lastIndexOf('/');
            String s = slash >= 0 ? internalName.substring(slash + 1) : internalName;
            return s.replace('$', '.');
        }

        /**
         * 为方法引用节点附着接收者注解与类型实参注解,无变化时返回原节点.
         *
         * <p>同一字节码偏移可同时存在接收者条目(0x45/0x46)与类型实参条目
         * (0x4A/0x4B),如 {@code @A C::<@A String>id}——按偏移成组消费,
         * 接收者注解渲染于所有者前,类型实参注解嵌入 {@code <...>} 实参列表.</p>
         */
        LambdaExpr apply(LambdaExpr node) {
            for (int i = cursor; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                RefInfo info = resolve(entry.offset);
                if (info == null || !matches(node, info)) {
                    continue;
                }
                List<String> receiverAnns = new ArrayList<>();
                String newName = node.methodRefName();
                int j = i;
                while (j < entries.size() && entries.get(j).offset() == entry.offset) {
                    Entry e = entries.get(j);
                    if (e.targetType() == TARGET_METHOD_REF_RECEIVER
                            || e.targetType() == TARGET_CTOR_REF_RECEIVER) {
                        receiverAnns.add(e.renderedAnn());
                    } else {
                        String nm = buildRefName(node, e, info);
                        if (nm != null) {
                            newName = nm;
                        }
                    }
                    j++;
                }
                cursor = j;
                if (receiverAnns.isEmpty() && newName.equals(node.methodRefName())) {
                    return node;
                }
                return LambdaExpr.methodRef(node.methodRefOwner(), newName,
                        node.functionalType(), receiverAnns);
            }
            return node;
        }

        /** 由字节码偏移找到 invokedynamic 并还原其 BootstrapMethods 元数据 */
        private RefInfo resolve(int offset) {
            if (method.instructions() == null) {
                return null;
            }
            for (Instruction ins : method.instructions()) {
                if (ins.offset() != offset || !"invokedynamic".equals(ins.mnemonic())) {
                    continue;
                }
                List<Integer> operands = ins.rawOperands();
                if (operands == null || operands.isEmpty()) {
                    return null;
                }
                int cpIndex = operands.get(0);
                ClassFileModel cf = context.classFile();
                if (cf == null || cf.constantPool() == null
                        || cpIndex <= 0 || cpIndex >= cf.constantPool().length) {
                    return null;
                }
                if (!(cf.constantPool()[cpIndex] instanceof ConstantPoolEntry.CpInvokeDynamic indy)) {
                    return null;
                }
                List<BootstrapMethodEntry> boots = context.bootstrapMethods();
                int bi = indy.bootstrapMethodAttrIndex();
                if (boots == null || bi < 0 || bi >= boots.size()) {
                    return null;
                }
                BootstrapMethodEntry boot = boots.get(bi);
                // metafactory 参数:[0] samMethodType [1] implMethod [2] instantiatedMethodType
                if (boot.arguments() == null || boot.arguments().size() < 3) {
                    return null;
                }
                ConstantPoolEntry[] pool = cf.constantPool();
                RefInfo info = new RefInfo();
                ConstantPoolEntry impl = pool[boot.arguments().get(1)];
                if (impl instanceof ConstantPoolEntry.CpMethodHandle mh) {
                    ConstantPoolEntry ref = pool[mh.referenceIndex()];
                    if (ref instanceof ConstantPoolEntry.CpMethodRef mr) {
                        fillImpl(info, pool, mr.classIndex(), mr.nameAndTypeIndex());
                    } else if (ref instanceof ConstantPoolEntry.CpInterfaceMethodRef imr) {
                        fillImpl(info, pool, imr.classIndex(), imr.nameAndTypeIndex());
                    }
                }
                ConstantPoolEntry inst = pool[boot.arguments().get(2)];
                if (inst instanceof ConstantPoolEntry.CpMethodType mt) {
                    info.instantiatedDescriptor = ConstantPoolParser.utf8(pool, mt.descriptorIndex());
                }
                if (info.name == null || info.instantiatedDescriptor == null) {
                    return null;
                }
                return info;
            }
            return null;
        }

        /** 构建带注解显式类型实参的方法引用名,如 {@code <@A String>id};失败返回 null */
        private String buildRefName(LambdaExpr node, Entry entry, RefInfo info) {
            if (entry.targetType() == TARGET_CTOR_REF_TYPE_ARGUMENT) {
                // 构造器引用:类型实参即被构造类的泛型实参(非泛型时为其类名)
                List<String> instParts = methodTypeParts(info.instantiatedDescriptor);
                String retText = instParts.isEmpty() ? null : instParts.getLast();
                String argText;
                if (retText != null && retText.contains("<")) {
                    int lt = retText.indexOf('<');
                    int gt = retText.lastIndexOf('>');
                    if (gt <= lt) {
                        return null;
                    }
                    argText = retText.substring(lt + 1, gt);
                } else {
                    argText = info.owner != null ? simpleName(info.owner) : null;
                }
                if (argText == null) {
                    return null;
                }
                return "<" + entry.renderedAnn() + " " + argText + ">" + node.methodRefName();
            }
            List<String> args = methodRefTypeArgs(info);
            if (args.isEmpty() || entry.typeArgIndex() >= args.size()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("<");
            for (int k = 0; k < args.size(); k++) {
                if (k > 0) {
                    sb.append(", ");
                }
                if (k == entry.typeArgIndex()) {
                    sb.append(entry.renderedAnn()).append(' ');
                }
                sb.append(args.get(k));
            }
            return sb.append('>').append(node.methodRefName()).toString();
        }

        /** 方法引用:计算显式类型实参列表(按类型形参声明顺序) */
        private List<String> methodRefTypeArgs(RefInfo info) {
            List<String> instParts = methodTypeParts(info.instantiatedDescriptor);
            // 优先用实现方法的 Signature 属性做精确映射
            MethodModel implMethod = info.implDescriptor != null
                    ? findImplMethodModel(info.name, info.implDescriptor) : null;
            if (implMethod != null && implMethod.signature() != null
                    && !implMethod.signature().isEmpty()) {
                List<String> typeVarNames = formalTypeParameterNames(implMethod.signature());
                JavaType[] sigParts = SignatureParser.parseMethodSignature(implMethod.signature());
                if (!typeVarNames.isEmpty() && sigParts != null
                        && sigParts.length == instParts.size()) {
                    java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
                    for (int p = 0; p < sigParts.length; p++) {
                        JavaType sig = sigParts[p];
                        // SignatureParser 将类型变量解析为 kind=TYPE_VARIABLE,
                        // 描述符 "T<名字>;"(internalName 携带变量名).
                        // CLASS 一并接受以兼容旧表示(如手工构建的 JavaType).
                        if (sig == null || (sig.kind() != TypeKind.CLASS
                                && sig.kind() != TypeKind.TYPE_VARIABLE)
                                || sig.descriptor() == null
                                || !sig.descriptor().startsWith("T")) {
                            continue;
                        }
                        String varName = sig.internalName();
                        if (varName != null) {
                            seen.putIfAbsent(varName, instParts.get(p));
                        }
                    }
                    List<String> args = new ArrayList<>();
                    for (String n : typeVarNames) {
                        String t = seen.get(n);
                        if (t == null) {
                            args = null;
                            break;
                        }
                        args.add(t);
                    }
                    if (args != null) {
                        return args;
                    }
                }
            }
            // 回退:实例化签名与擦除签名的差异位置(去重,按出现顺序)
            List<String> implParts = info.implDescriptor != null
                    ? methodTypeParts(info.implDescriptor) : null;
            Set<String> candidates = new LinkedHashSet<>();
            for (int p = 0; p < instParts.size(); p++) {
                String instPart = instParts.get(p);
                if ("void".equals(instPart)) {
                    continue;
                }
                String implPart = implParts != null && p < implParts.size()
                        ? implParts.get(p) : null;
                if (implPart == null || !implPart.equals(instPart)) {
                    candidates.add(instPart);
                }
            }
            if (candidates.isEmpty()) {
                // 显式类型实参与擦除签名相同(如 ::<Object>id):退回全部实例化位置
                for (String part : instParts) {
                    if (!"void".equals(part)) {
                        candidates.add(part);
                    }
                }
            }
            return new ArrayList<>(candidates);
        }

        /** 在 class 文件模型中按 (名称, 描述符) 查找实现方法模型 */
        private MethodModel findImplMethodModel(String name, String descriptor) {
            ClassFileModel cf = context.classFile();
            if (cf == null || cf.methods() == null) {
                return null;
            }
            for (MethodModel m : cf.methods()) {
                if (name.equals(m.name()) && descriptor.equals(m.descriptor())) {
                    return m;
                }
            }
            return null;
        }

        /** 类型注解条目:偏移,目标类型,类型实参下标,渲染后的注解行 */
        private record Entry(int offset, int targetType, int typeArgIndex, String renderedAnn) {}

        /** 经 invokedynamic 还原的方法引用信息 */
        private static final class RefInfo {

            String owner;                // 实现方法所有者(内部名)

            String name;                 // 实现方法名("<init>" 为构造器)

            String implDescriptor;       // 实现(擦除)方法描述符

            String instantiatedDescriptor; // 实例化方法类型描述符(可含泛型)
        }

        /** 描述符片段渲染结果:源码文本与下一解析位置 */
        private record TypeText(String text, int next) {}
    }
}
