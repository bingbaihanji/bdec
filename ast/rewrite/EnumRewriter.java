package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.AccessFlags;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.semantic.SemanticReconstructor;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.util.ParameterNameResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 枚举重写器,检测枚举类并还原 {@code enum} 关键字,
 * 移除 javac 生成的合成成员({@code $VALUES},{@code values()} 和 {@code valueOf(String)}).
 *
 * <p>设计参考 Vineflower 的 {@code EnumProcessor}.
 */
public class EnumRewriter implements RewriteRule {

    // ======== JVM 常量压栈操作码 ========
    private static final int OP_ICONST_M1 = 2;

    private static final int OP_ICONST_0 = 3;

    private static final int OP_ICONST_1 = 4;

    private static final int OP_ICONST_2 = 5;

    private static final int OP_ICONST_3 = 6;

    private static final int OP_ICONST_4 = 7;

    private static final int OP_ICONST_5 = 8;

    private static final int OP_BIPUSH = 16;

    private static final int OP_SIPUSH = 17;

    private static final int OP_LDC = 18;

    private static final int OP_LDC_W = 19;

    private static final int OP_LDC2_W = 20;

    private static final int OP_NEW = 187;

    private static final int OP_DUP = 89;

    private static final int OP_INVOKESPECIAL = 183;

    private static final int OP_PUTSTATIC = 179;

    /**
     * 将一条压栈指令转换为其 Java 源文本表示.
     * 支持 ICONST,BIPUSH,SIPUSH,LDC,LDC_W 和 LDC2_W.
     */
    private static String pushValueToText(Instruction insn, ConstantPoolEntry[] pool) {
        int op = insn.opcode();
        // ICONST_M1 .. ICONST_5:将操作码映射为整数值
        if (op >= OP_ICONST_M1 && op <= OP_ICONST_5) {
            int val = op - OP_ICONST_0;
            return String.valueOf(val);
        }
        // BIPUSH:操作数为单字节有符号整数
        if (op == OP_BIPUSH && !insn.rawOperands().isEmpty()) {
            int val = (byte) insn.rawOperands().get(0).intValue();
            return String.valueOf(val);
        }
        // SIPUSH:操作数为 16 位有符号短整数
        if (op == OP_SIPUSH && !insn.rawOperands().isEmpty()) {
            int val = (short) insn.rawOperands().get(0).intValue();
            return String.valueOf(val);
        }
        // LDC 或 LDC_W:通过 rawOperands[0] 中的常量池索引查找值
        if ((op == OP_LDC || op == OP_LDC_W) && !insn.rawOperands().isEmpty()) {
            return ldcValueToText(insn.rawOperands().get(0), pool);
        }
        // LDC2_W:常量池索引在 rawOperands[0] 中(long 或 double 类型)
        if (op == OP_LDC2_W && !insn.rawOperands().isEmpty()) {
            return ldcValueToText(insn.rawOperands().get(0), pool);
        }
        return null;
    }

    /** 查找常量池条目并将其转换为 Java 源文本表示 */
    private static String ldcValueToText(int poolIdx, ConstantPoolEntry[] pool) {
        ConstantPoolEntry entry = pool[poolIdx];
        if (entry instanceof ConstantPoolEntry.CpInteger cpi) {
            return String.valueOf(cpi.value());
        }
        if (entry instanceof ConstantPoolEntry.CpString cps) {
            return "\"" + ConstantPoolParser.utf8(pool, cps.stringIndex()) + "\"";
        }
        if (entry instanceof ConstantPoolEntry.CpFloat cpf) {
            return String.valueOf(cpf.value()) + "F";
        }
        if (entry instanceof ConstantPoolEntry.CpLong cpl) {
            return String.valueOf(cpl.value()) + "L";
        }
        if (entry instanceof ConstantPoolEntry.CpDouble cpd) {
            return String.valueOf(cpd.value()) + "D";
        }
        if (entry instanceof ConstantPoolEntry.CpClass cpc) {
            return ConstantPoolParser.utf8(pool, cpc.nameIndex())
                    .replace('/', '.') + ".class";
        }
        return null;
    }

    /**
     * 从 PUTSTATIC 指令中提取字段名.
     * PUTSTATIC 的操作数为 2 字节常量池索引,在指令解码器中被编码为单个整数
     * (来自 {@code readUnsignedShort()}).
     */
    private static String fieldNameFromPutstatic(Instruction insn,
                                                 ConstantPoolEntry[] pool) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int poolIdx = insn.rawOperands().get(0);
        ConstantPoolEntry entry = pool[poolIdx];
        if (entry instanceof ConstantPoolEntry.CpFieldRef fr) {
            ConstantPoolEntry nat = pool[fr.nameAndTypeIndex()];
            if (nat instanceof ConstantPoolEntry.CpNameAndType nt) {
                return ConstantPoolParser.utf8(pool, nt.nameIndex());
            }
        }
        return null;
    }

    /**
     * 反编译内部枚举常量类中的非构造器方法,返回其源文本.
     * unit 提供当前编译单元的 import 列表与内部类名称映射;
     * collectedImports 收集常量体内使用但外层未 import 的类型,
     * 由 rewrite() 合并进最终 import 列表.
     */
    private static String decompileInnerClassMethods(ClassFileModel inner,
                                                     DecompileContext ctx,
                                                     CompilationUnit unit,
                                                     Set<String> collectedImports) {
        CfgBuilder cfgBuilder = new CfgBuilder();
        IrBuilder irBuilder = new IrBuilder();
        SemanticReconstructor sr = new SemanticReconstructor();
        ControlFlowStructurer structurer = new ControlFlowStructurer();

        List<String> methodSources = new ArrayList<>();
        for (MethodModel method : inner.methods()) {
            // 跳过构造器和静态初始化器
            if ("<init>".equals(method.name())) {
                continue;
            }
            if ("<clinit>".equals(method.name())) {
                continue;
            }
            // 跳过抽象方法和 native 方法
            if (method.isAbstract() || method.isNative()) {
                continue;
            }
            // 跳过冗余桥接方法(ACC_BRIDGE):
            // javac 为带体的枚举常量生成 E$N 类时,若常量覆写了泛型接口方法
            // (如 I<T>.get()),会同时生成擦除签名版本的桥接方法
            // (如 Object get()),其转发到真实实现.当同名,擦除后参数一致
            // 的非桥接方法存在时必须过滤,否则输出含重复/无法编译的方法;
            // 若桥接方法是唯一实现(罕见),则保留以免丢失语义.
            if (isRedundantBridge(method, inner.methods())) {
                continue;
            }

            try {
                ControlFlowGraph cfg = cfgBuilder.build(method);
                LinearIr ir = irBuilder.build(cfg, method,
                        inner.constantPool(), inner.bootstrapMethods());
                ir = sr.reconstruct(ir, method, cfg, inner);
                StructuredMethod sm = structurer.structure(ir, ctx);

                if (sm.body() == null) {
                    continue;
                }

                // 从方法模型中构建参数名称和类型
                String[] paramNames = buildParamNames(method);
                JavaType[] paramTypes = method.parameterTypes();
                // 方法级类型参数(如 <T> T id(T x)),随声明一并输出;
                // 泛型签名(Signature 属性)覆盖描述符类型,与 AstBuilder 方法路径
                // 同一约定;否则常量体方法签名输出擦除的原始类型
                // (如 Map<String, List<Integer>> get() 变成 Map get())
                List<String> methodTypeParams = method.signature() != null
                        && !method.signature().isEmpty()
                        ? SignatureParser.extractMethodTypeParams(method.signature())
                        : List.of();
                JavaType returnType = applyGenericSignature(method, inner,
                        paramTypes, methodTypeParams);

                MethodDeclaration md = new MethodDeclaration(
                        method.accessFlags(),
                        method.name(),
                        returnType,
                        paramNames,
                        paramTypes,
                        methodTypeParams,
                        sm.body()
                );

                // 收集方法签名类型(返回类型+参数类型)的 import:
                // 常量体可能引用外层枚举完全未使用的泛型类型
                // (如非覆写的辅助方法),其嵌套实参无法由 AstBuilder 收集,
                // 此处用 TypeText.render 与常规字段路径同一约定补齐,
                // 保证短名渲染且输出可重新编译.
                com.bingbaihanji.bdec.util.TypeText.render(md.returnType(),
                        unit.packageName(), unit.innerClassNames(), collectedImports);
                if (md.parameterTypes() != null) {
                    for (JavaType pt : md.parameterTypes()) {
                        com.bingbaihanji.bdec.util.TypeText.render(pt,
                                unit.packageName(), unit.innerClassNames(),
                                collectedImports);
                    }
                }
                // 局部变量声明的类型(如 List<String> x = null;)不暴露于签名,
                // 从变量表收集 import,否则常量体局部变量输出全限定名
                // (与 AstBuilder.collectBodyImports 同约定).null 赋值变量的
                // base type 为 Object,真实类型在 LVTT 的 genericType 中,两者都收集.
                if (sm.ir() != null && sm.ir().variables() != null) {
                    for (var v : sm.ir().variables()) {
                        com.bingbaihanji.bdec.util.TypeText.render(v.genericType(),
                                unit.packageName(), unit.innerClassNames(),
                                collectedImports);
                        com.bingbaihanji.bdec.util.TypeText.render(v.type(),
                                unit.packageName(), unit.innerClassNames(),
                                collectedImports);
                    }
                }

                // 将单个方法输出为源字符串(既有 imports + 本轮新收集的 import
                // 一起生效,使签名与局部变量类型均以短名渲染)
                List<String> effectiveImports = com.bingbaihanji.bdec.util.TypeText
                        .mergeImports(unit.imports(), collectedImports);
                String src = emitSingleMethod(md, unit.packageName(), effectiveImports);
                if (src != null && !src.isEmpty()) {
                    methodSources.add(src);
                }
            } catch (Exception e) {
                // 方法反编译失败,跳过该方法
            }
        }

        if (methodSources.isEmpty()) {
            return "";
        }

        // 构建匿名类体:{ method1 method2 ... }
        StringBuilder sb = new StringBuilder();
        sb.append(" {\n");
        for (String ms : methodSources) {
            for (String line : ms.split("\n")) {
                sb.append("        ").append(line).append("\n");
            }
        }
        sb.append("    }");
        return sb.toString();
    }

    /**
     * 判断方法是否为冗余的 ACC_BRIDGE 桥接方法.
     * 仅当同一类中存在同名,参数擦除后签名一致的非桥接方法时才视为冗余;
     * 若桥接方法是唯一实现,则返回 false(必须保留).
     */
    private static boolean isRedundantBridge(MethodModel method, List<MethodModel> all) {
        if ((method.accessFlags() & AccessFlags.ACC_BRIDGE) == 0) {
            return false;
        }
        String params = erasedParams(method.descriptor());
        for (MethodModel other : all) {
            if (other == method) {
                continue;
            }
            if ((other.accessFlags() & AccessFlags.ACC_BRIDGE) != 0) {
                continue; // 其他桥接方法不构成"真实实现"
            }
            if (!method.name().equals(other.name())) {
                continue;
            }
            if (erasedParams(other.descriptor()).equals(params)) {
                return true;
            }
        }
        return false;
    }

    /** 从方法描述符中提取参数部分(即擦除后的参数签名). */
    private static String erasedParams(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return descriptor;
        }
        return descriptor.substring(1, close);
    }

    /**
     * 从方法模型的局部变量表中构建参数名称数组,
     * 若不可用则回退到合成名称.
     */
    private static String[] buildParamNames(MethodModel method) {
        return ParameterNameResolver.resolveNames(method, "param");
    }


    /**
     * 使用 StatementEmitter 配合临时 IndentWriter
     * 将单个 MethodDeclaration 输出为源字符串.
     * imports 为编译单元当前的 import 列表,使常量体内的方法签名与
     * 局部变量类型获得与主类一致的 import 感知短名渲染.
     */
    private static String emitSingleMethod(MethodDeclaration md, String packageName,
                                           List<String> imports) {
        com.bingbaihanji.bdec.emit.IndentWriter w =
                new com.bingbaihanji.bdec.emit.IndentWriter(4);
        com.bingbaihanji.bdec.emit.ExpressionEmitter exprs =
                new com.bingbaihanji.bdec.emit.ExpressionEmitter(w, packageName, imports);
        com.bingbaihanji.bdec.emit.StatementEmitter stmts =
                new com.bingbaihanji.bdec.emit.StatementEmitter(w, exprs,
                        "Enum", false);
        stmts.emit(md);
        return w.toString().trim();
    }

    /**
     * 应用方法的泛型签名(Signature 属性)覆盖描述符类型.
     *
     * <p>{@code MethodModel.returnType()}/{@code parameterTypes()} 来自方法描述符,
     * 是擦除后的类型(不含泛型实参).此处与 AstBuilder 方法路径采用同一约定:
     * 仅当签名类型比擦除类型信息更丰富(类型参数,泛型实参/通配符,
     * 类型变量数组元素)时才替换,防止签名解析差异破坏无泛型方法.
     * 参数数组在调用方就地替换(与 AstBuilder 相同的约定).
     *
     * @param method           方法模型
     * @param inner            常量体内部类模型(提供类级类型参数上下文)
     * @param paramTypes       描述符参数类型数组(就地替换,可为 null)
     * @param methodTypeParams 方法级类型参数名称列表
     * @return 覆盖后的返回类型(无覆盖时返回描述符返回类型)
     */
    private static JavaType applyGenericSignature(MethodModel method,
                                                  ClassFileModel inner,
                                                  JavaType[] paramTypes,
                                                  List<String> methodTypeParams) {
        if (method.signature() == null || method.signature().isEmpty()
                || paramTypes == null) {
            return method.returnType();
        }
        JavaType[] sigTypes = SignatureParser.parseMethodSignature(method.signature());
        if (sigTypes == null || sigTypes.length != paramTypes.length + 1) {
            return method.returnType();
        }
        List<String> classTypeParams = SignatureParser.extractTypeParams(
                inner != null ? inner.signature() : "");
        for (int si = 0; si < paramTypes.length; si++) {
            JavaType sig = sigTypes[si];
            if (isTypeParam(sig, classTypeParams) || isTypeParam(sig, methodTypeParams)
                    || hasGenericsOrWildcard(sig) || hasTypeVariableArrayElement(sig)) {
                paramTypes[si] = sig;
            }
        }
        JavaType sigRet = sigTypes[sigTypes.length - 1];
        if (isTypeParam(sigRet, classTypeParams) || isTypeParam(sigRet, methodTypeParams)
                || hasGenericsOrWildcard(sigRet) || hasTypeVariableArrayElement(sigRet)) {
            return sigRet;
        }
        return method.returnType();
    }

    /** 类型是否为指定的类型参数(类型变量以内部名称携带变量名,与 kind 无关). */
    private static boolean isTypeParam(JavaType type, List<String> typeParams) {
        if (type == null || typeParams == null || typeParams.isEmpty()) {
            return false;
        }
        String name = type.internalName();
        return name != null && typeParams.contains(name);
    }

    /** 类型是否携带泛型实参或通配符(签名比擦除描述符信息更丰富). */
    private static boolean hasGenericsOrWildcard(JavaType t) {
        if (t == null) {
            return false;
        }
        if (!t.typeArguments().isEmpty()) {
            return true;
        }
        // ARRAY 的泛型信息位于元素类型(如 List<String>[]):递归元素判定.
        if (t.kind() == TypeKind.ARRAY && t.element() != null) {
            return hasGenericsOrWildcard(t.element());
        }
        return false;
    }

    /** 数组的基元素是否为签名格式的类型变量(裸 {@code T[]}). */
    private static boolean hasTypeVariableArrayElement(JavaType t) {
        if (t == null || t.kind() != TypeKind.ARRAY || t.descriptor() == null) {
            return false;
        }
        String baseDesc = t.descriptor().replaceFirst("^\\[+", "");
        return baseDesc.length() > 2 && baseDesc.charAt(0) == 'T'
                && baseDesc.charAt(baseDesc.length() - 1) == ';';
    }

    /** 从 NEW 指令的常量池引用中提取类内部名称(如 {@code pkg/E$1}),失败返回 null. */
    private static String classNameOfNew(Instruction insn, ConstantPoolEntry[] pool) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int poolIdx = insn.rawOperands().get(0);
        ConstantPoolEntry entry = pool[poolIdx];
        if (entry instanceof ConstantPoolEntry.CpClass cpc) {
            return ConstantPoolParser.utf8(pool, cpc.nameIndex());
        }
        return null;
    }

    @Override
    public String name() {return "enum";}

    @Override
    public RewriteRuleKind kind() {return RewriteRuleKind.ENUM;}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        Set<String> collectedImports = new java.util.HashSet<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, context, unit, collectedImports));
        }
        return new CompilationUnit(unit.packageName(),
                com.bingbaihanji.bdec.util.TypeText.mergeImports(
                        unit.imports(), collectedImports),
                types, unit.innerClassNames());
    }

    /**
     * 重写类型声明.如果是枚举类,则还原 enum 关键字并清理合成成员;
     * 如果不是枚举类,则原样返回.
     */
    private TypeDeclaration rewriteType(TypeDeclaration td, DecompileContext ctx,
                                        CompilationUnit unit,
                                        Set<String> collectedImports) {
        if (!isEnum(td)) {
            return td;
        }

        // 从 <clinit> 字节码中提取枚举常量的构造器参数,
        // 以及每个带匿名类体的常量对应的内部类名(E$1 等).
        // javac 对常量体匿名类的编号按"带体常量出现的顺序",与常量序数无必然
        // 对应关系,因此必须以 <clinit> 中 NEW 指令引用的实际类名为准
        // (枚举未声明抽象方法时 javac 仍会为带体常量生成 E$N 类,
        // 例如实现接口的常量体,覆写 toString 的常量体).
        Map<String, String> bodyClasses = new HashMap<>();
        Map<String, String> constArgs = extractConstantArgs(td, ctx, bodyClasses);

        // 分离枚举常量字段和其他成员
        List<String> enumConstants = new ArrayList<>();
        List<AstNode> regularMembers = new ArrayList<>();

        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd) {
                if (isValuesField(fd)) {
                    continue; // 跳过 $VALUES 合成字段
                }
                if (isEnumConstantField(fd, td.simpleName(), unit)) {
                    String args = constArgs.getOrDefault(fd.name(), "");
                    String body = "";
                    String innerName = bodyClasses.get(fd.name());
                    if (innerName != null) {
                        // 该常量带匿名类体,加载其内部类并反编译其中的方法
                        body = buildAnonymousClassBody(innerName, ctx, unit,
                                collectedImports);
                    }
                    // 常量字段上的 RUNTIME 注解(如 @Deprecated,Java 9 起
                    // RUNTIME retention)落在字段的 RuntimeVisibleAnnotations,
                    // 内联在常量名之前输出 "@Deprecated A"
                    List<String> constAnns = fd.annotations();
                    String annPrefix = constAnns.isEmpty() ? ""
                            : String.join(" ", constAnns) + " ";
                    enumConstants.add(annPrefix + fd.name() + args + body);
                    continue;
                }
            }
            if (m instanceof MethodDeclaration md) {
                if (isEnumSyntheticMethod(md)) {
                    continue; // 跳过合成 values() 和 valueOf() 方法
                }
                if (isEnumStaticInit(md)) {
                    continue; // 跳过 <clinit> 静态初始化器
                }
                if (isEnumConstructor(md, td.simpleName())) {
                    // 修正枚举构造器:去掉 String name 和 int ordinal 两个隐式参数
                    regularMembers.add(fixEnumConstructor(md));
                    continue;
                }
            }
            regularMembers.add(m);
        }

        // 收集常规字段类型的 import:StatementEmitter 用 import 感知的短名渲染
        // 字段类型,但 AstBuilder 的字段路径只收集顶层类型的 import
        // (如 Map<String, List<Integer>> 只收集 java.util.Map),此处补齐
        // 嵌套泛型实参的 import,保证输出可重新编译.
        for (AstNode m : regularMembers) {
            if (m instanceof FieldDeclaration fd) {
                com.bingbaihanji.bdec.util.TypeText.render(fd.type(),
                        unit.packageName(), unit.innerClassNames(), collectedImports);
            }
        }

        // 将枚举常量列表作为特殊的字段标记输出
        List<AstNode> members = new ArrayList<>();
        if (!enumConstants.isEmpty()) {
            String constList = String.join(",\n    ", enumConstants)
                    + (enumConstants.size() == 1 ? ";" : "\n    ;");
            members.add(new FieldDeclaration(0, "$enumConstants$",
                    com.bingbaihanji.bdec.type.JavaType.VOID,
                    new com.bingbaihanji.bdec.ast.expr.VarExpr(constList)));
        }
        members.addAll(regularMembers);

        // 清除 ACC_ENUM 和 ACC_ABSTRACT 标志位,将 kindName 改为 "enum".
        // 保留类级注解,父类型注解与接口注解(枚举的父接口注解
        // 如 "implements @A Runnable" 由 CLASS_EXTENDS 的接口下标编码)
        int flags = (td.accessFlags() & ~(AccessFlags.ACC_ENUM | AccessFlags.ACC_ABSTRACT));
        return new TypeDeclaration(flags, td.simpleName(), "enum", null,
                td.interfaceNames(), td.typeParameters(), members, td.annotations(),
                td.superAnnotations(), td.interfaceAnnotations());
    }

    /**
     * 通过解析 {@code <clinit>} 字节码提取每个枚举常量的构造器参数,
     * 并在 {@code bodyClasses}(可空)中记录带匿名类体的常量
     * 对应的内部类名(字段名 → 内部名称,如 {@code A → pkg/E$1}).
     */
    private Map<String, String> extractConstantArgs(TypeDeclaration td,
                                                    DecompileContext ctx,
                                                    Map<String, String> bodyClasses) {
        Map<String, String> result = new HashMap<>();
        ClassFileModel cfm = resolveEnumClassFile(td, ctx);
        if (cfm == null) {
            return result;
        }

        // 查找 <clinit> 方法
        MethodModel clinit = null;
        for (MethodModel m : cfm.methods()) {
            if ("<clinit>".equals(m.name())) {
                clinit = m;
                break;
            }
        }
        if (clinit == null || clinit.instructions() == null) {
            return result;
        }

        List<Instruction> insns = clinit.instructions();
        ConstantPoolEntry[] pool = cfm.constantPool();

        // 扫描字节码指令序列,识别枚举常量创建模式:NEW → DUP → 压参数 → INVOKESPECIAL → PUTSTATIC
        for (int i = 0; i < insns.size(); i++) {
            Instruction insn = insns.get(i);
            if (insn.opcode() != OP_NEW) {
                continue;
            }
            // 期望下一条指令为 DUP
            if (i + 1 >= insns.size() || insns.get(i + 1).opcode() != OP_DUP) {
                continue;
            }

            // 收集 DUP 之后,INVOKESPECIAL 之前压入栈的参数
            int argStart = i + 2;
            List<String> argTexts = new ArrayList<>();
            int j = argStart;
            while (j < insns.size() && insns.get(j).opcode() != OP_INVOKESPECIAL) {
                String argText = pushValueToText(insns.get(j), pool);
                if (argText != null) {
                    argTexts.add(argText);
                }
                j++;
            }

            // 期望 INVOKESPECIAL 之后为 PUTSTATIC
            if (j >= insns.size() || insns.get(j).opcode() != OP_INVOKESPECIAL) {
                continue;
            }
            if (j + 1 >= insns.size() || insns.get(j + 1).opcode() != OP_PUTSTATIC) {
                continue;
            }

            // 从 PUTSTATIC 指令获取字段名
            Instruction putstatic = insns.get(j + 1);
            String fieldName = fieldNameFromPutstatic(putstatic, pool);

            // 该常量的 NEW 类名若不同于枚举类本身(如 E$1),
            // 说明该常量带有匿名类体
            if (bodyClasses != null && fieldName != null) {
                String newClass = classNameOfNew(insn, pool);
                if (newClass != null && !newClass.equals(cfm.internalName())) {
                    bodyClasses.put(fieldName, newClass);
                }
            }

            // 前两个参数为合成参数(name 字符串和 ordinal 整数),用户参数从索引 2 开始
            if (argTexts.size() > 2 && fieldName != null) {
                List<String> userArgs = argTexts.subList(2, argTexts.size());
                result.put(fieldName, "(" + String.join(", ", userArgs) + ")");
            }

            i = j + 1; // 跳过已处理的指令序列
        }

        return result;
    }

    /**
     * 解析当前枚举类型自身的 ClassFileModel.
     *
     * <p>嵌套枚举(如 {@code Outer$Color})的常量初始化在它自己的 {@code <clinit>}
     * 里(如 {@code new Outer$Color$1; putstatic Outer$Color.RED}),不在顶层类的
     * clinit 中.若直接用 {@code ctx.classFile()}(顶层类)扫描,常量体映射
     * {@code bodyClasses} 为空,嵌套枚举的常量匿名体(覆写抽象方法)会全部丢失.
     * 顶层枚举时 {@code ctx.classFile()} 即枚举自身,直接使用.</p>
     */
    private ClassFileModel resolveEnumClassFile(TypeDeclaration td, DecompileContext ctx) {
        ClassFileModel cfm = ctx.classFile();
        if (cfm == null) {
            return null;
        }
        String internal = cfm.internalName();
        // 按最后 $ 段判断 classFile 是否即当前枚举:顶层枚举(pkg/EnumDemo),嵌套枚举经
        // InnerClassDecompiler 独立反编译(Outer$Color)时 classFile 均为枚举自身.
        int dollar = internal.lastIndexOf('$');
        String lastSeg;
        if (dollar >= 0) {
            lastSeg = internal.substring(dollar + 1);
        } else {
            int slash = internal.lastIndexOf('/');
            lastSeg = slash >= 0 ? internal.substring(slash + 1) : internal;
        }
        if (lastSeg.equals(td.simpleName())) {
            return cfm; // 已是枚举自身
        }
        // 嵌套枚举但 classFile 是外层类:加载 <outer>$<simpleName>.class
        String nested = internal + "$" + td.simpleName();
        byte[] bytes = ctx.loadClassBytes(nested);
        if (bytes == null) {
            return null;
        }
        try {
            return new ClassFileReader().read(nested, bytes);
        } catch (IOException e) {
            return null;
        }
    }

    /** 检查字段是否为枚举常量(public static final 且类型与枚举类型相同) */
    private boolean isEnumConstantField(FieldDeclaration fd, String enumName,
                                        CompilationUnit unit) {
        int flags = fd.accessFlags();
        int required = AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC | AccessFlags.ACC_FINAL;
        boolean isPublicStaticFinal = (flags & required) == required; // public static final
        if (!isPublicStaticFinal) {
            return false;
        }
        // 用 import 感知的短名渲染(与输出文本一致),避免 displayName 的
        // 全限定形态因包名巧合(如包名包含枚举名)造成误判
        String typeStr = fd.type() != null
                ? com.bingbaihanji.bdec.util.TypeText.render(fd.type(),
                unit.packageName(), unit.innerClassNames(), null)
                : "";
        return typeStr.contains(enumName);
    }

    /** 判断类型声明是否为枚举类(检查 ACC_ENUM 标志位) */
    private boolean isEnum(TypeDeclaration td) {
        return (td.accessFlags() & AccessFlags.ACC_ENUM) != 0;
    }

    /** 判断字段是否为合成的 $VALUES 数组字段 */
    private boolean isValuesField(FieldDeclaration fd) {
        return "$VALUES".equals(fd.name());
    }

    // ======== 匿名类体支持 ========

    /** 判断方法是否为 javac 生成的枚举合成方法(values() 或 valueOf(String)) */
    private boolean isEnumSyntheticMethod(MethodDeclaration md) {
        String name = md.name();
        if ("values".equals(name) || name != null && name.startsWith("$values")) {
            return md.parameterNames().length == 0 && md.isStatic();
        }
        if ("valueOf".equals(name)) {
            return md.parameterNames().length == 1 && md.isStatic();
        }
        return false;
    }

    /** 判断方法是否为枚举的静态初始化器(<clinit>) */
    private boolean isEnumStaticInit(MethodDeclaration md) {
        return md.name() == null;
    }

    /** 判断方法是否为枚举构造器(至少包含 name 和 ordinal 两个合成参数) */
    private boolean isEnumConstructor(MethodDeclaration md, String enumName) {
        if (!enumName.equals(md.name()) && !"<init>".equals(md.name())) {
            return false;
        }
        return md.parameterNames().length >= 2;
    }

    /**
     * 修正枚举构造器:移除前两个合成参数(String name, int ordinal),
     * 并清理构造器体中对 super() 的调用.
     */
    private MethodDeclaration fixEnumConstructor(MethodDeclaration md) {
        int origLen = md.parameterNames().length;
        if (origLen < 2) {
            return md;
        }
        int newLen = origLen - 2;
        String[] newNames = new String[newLen];
        com.bingbaihanji.bdec.type.JavaType[] newTypes = new com.bingbaihanji.bdec.type.JavaType[newLen];
        System.arraycopy(md.parameterNames(), 2, newNames, 0, newLen);
        System.arraycopy(md.parameterTypes(), 2, newTypes, 0, newLen);
        // 参数注解同步丢弃前 2 个合成参数(name, ordinal)
        String[] newParamAnns = null;
        if (md.parameterAnnotations() != null) {
            newParamAnns = new String[newLen];
            System.arraycopy(md.parameterAnnotations(), 2, newParamAnns, 0, newLen);
        }

        Statement body = md.body() != null ? cleanEnumConstructorBody(md.body()) : null;

        return withParamsAndBody(md, newNames, newTypes, newParamAnns, body);
    }

    /**
     * 清理枚举构造器体中的合成语句:
     * 移除编译器生成的局部变量声明和 super() 调用.
     */
    private Statement cleanEnumConstructorBody(Statement body) {
        if (!(body instanceof BlockStatement bs)) {
            return body;
        }
        List<Statement> filtered = new ArrayList<>();
        for (Statement s : bs.statements()) {
            // 移除编译器插入的局部变量声明(形式为 varN=0)
            if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                    && vd.name().startsWith("var")
                    && vd.initializer() != null
                    && vd.initializer() instanceof com.bingbaihanji.bdec.ast.expr.LitExpr l
                    && l.value() instanceof Integer i && i == 0) {
                continue;
            }
            // 移除 super() 父类构造器调用
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "super".equals(inv.methodName())) {
                continue;
            }
            filtered.add(s);
        }
        if (filtered.isEmpty()) {
            return new BlockStatement(List.of());
        }
        return new BlockStatement(filtered);
    }

    /**
     * 为枚举常量构建匿名类体源文本,通过加载并反编译其内部类来实现.
     *
     * @param internalName 该常量匿名类体的内部名称(如 {@code pkg/E$1}),
     *                     由 {@code <clinit>} 中的 NEW 指令精确确定
     */
    private String buildAnonymousClassBody(String internalName, DecompileContext ctx,
                                           CompilationUnit unit,
                                           Set<String> collectedImports) {
        byte[] bytes = ctx.loadClassBytes(internalName);
        if (bytes == null) {
            return "";
        }

        try {
            ClassFileModel inner = new ClassFileReader().read(internalName, bytes);
            String bodies = decompileInnerClassMethods(inner, ctx, unit,
                    collectedImports);
            return bodies;
        } catch (IOException e) {
            return "";
        }
    }
}
