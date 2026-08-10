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
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.semantic.SemanticReconstructor;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举重写器,检测枚举类并还原 {@code enum} 关键字,
 * 移除 javac 生成的合成成员({@code $VALUES},{@code values()} 和 {@code valueOf(String)}).
 *
 * <p>设计参考 Vineflower 的 {@code EnumProcessor}.
 */
public class EnumRewriter implements RewriteRule {

    /** ACC_ENUM 标志位(0x4000) */
    private static final int ACC_ENUM = 0x4000;

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
     * 检查枚举是否包含抽象方法,若包含则需要在枚举常量上生成匿名类体.
     */
    private static boolean hasAbstractMethods(TypeDeclaration td) {
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md
                    && (md.accessFlags() & 0x0400) != 0) { // ACC_ABSTRACT
                return true;
            }
        }
        return false;
    }

    /** 从内部名称中提取包名部分 */
    private static String packageOf(String internalName) {
        int idx = internalName.lastIndexOf('/');
        return idx >= 0 ? internalName.substring(0, idx) : "";
    }

    /**
     * 反编译内部枚举常量类中的非构造器方法,返回其源文本.
     */
    private static String decompileInnerClassMethods(ClassFileModel inner,
                                                     DecompileContext ctx) {
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

                MethodDeclaration md = new MethodDeclaration(
                        method.accessFlags(),
                        method.name(),
                        method.returnType(),
                        paramNames,
                        paramTypes,
                        List.of(),
                        sm.body()
                );

                // 将单个方法输出为源字符串
                String src = emitSingleMethod(md);
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
     * 从方法模型的局部变量表中构建参数名称数组,
     * 若不可用则回退到合成名称.
     */
    private static String[] buildParamNames(MethodModel method) {
        int count = method.parameterTypes() != null
                ? method.parameterTypes().length : 0;
        String[] names = new String[count];
        Map<Integer, String> lvt = method.localVarNames();
        // 参数从槽位 0 开始(实例方法从槽位 1 开始,因为 0 槽位留给 this)
        int slot = method.isStatic() ? 0 : 1;
        for (int i = 0; i < count; i++) {
            String name = lvt != null ? lvt.get(slot) : null;
            names[i] = (name != null) ? name : ("param" + i);
            JavaType pt = method.parameterTypes() != null
                    && i < method.parameterTypes().length
                    ? method.parameterTypes()[i] : JavaType.classType("java/lang/Object");
            slot += pt.slotCount();
        }
        return names;
    }

    /**
     * 使用 StatementEmitter 配合临时 IndentWriter
     * 将单个 MethodDeclaration 输出为源字符串.
     */
    private static String emitSingleMethod(MethodDeclaration md) {
        com.bingbaihanji.bdec.emit.IndentWriter w =
                new com.bingbaihanji.bdec.emit.IndentWriter(4);
        com.bingbaihanji.bdec.emit.ExpressionEmitter exprs =
                new com.bingbaihanji.bdec.emit.ExpressionEmitter(w, List.of());
        com.bingbaihanji.bdec.emit.StatementEmitter stmts =
                new com.bingbaihanji.bdec.emit.StatementEmitter(w, exprs,
                        "Enum", false);
        stmts.emit(md);
        return w.toString().trim();
    }

    @Override
    public String name() {return "enum";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types, unit.innerClassNames());
    }

    /**
     * 重写类型声明.如果是枚举类,则还原 enum 关键字并清理合成成员;
     * 如果不是枚举类,则原样返回.
     */
    private TypeDeclaration rewriteType(TypeDeclaration td, DecompileContext ctx) {
        if (!isEnum(td)) {
            return td;
        }

        // 从 <clinit> 字节码中提取枚举常量的构造器参数
        Map<String, String> constArgs = extractConstantArgs(td, ctx);

        // 检查该枚举是否包含抽象方法(需要匿名类体)
        boolean hasAbstract = hasAbstractMethods(td);

        // 分离枚举常量字段和其他成员
        List<String> enumConstants = new ArrayList<>();
        List<AstNode> regularMembers = new ArrayList<>();

        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd) {
                if (isValuesField(fd)) {
                    continue; // 跳过 $VALUES 合成字段
                }
                if (isEnumConstantField(fd, td.simpleName())) {
                    String args = constArgs.getOrDefault(fd.name(), "");
                    String body = "";
                    if (hasAbstract) {
                        // 尝试加载该枚举常量的匿名类体
                        // (例如,序数 0 的枚举常量对应 InnerClass Demo$1)
                        body = buildAnonymousClassBody(fd.name(), args,
                                td.simpleName(), enumConstants.size(), ctx);
                    }
                    enumConstants.add(fd.name() + args + body);
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

        // 清除 ACC_ENUM 和 ACC_ABSTRACT 标志位,将 kindName 改为 "enum"
        int flags = (td.accessFlags() & ~(ACC_ENUM | 0x0400));
        return new TypeDeclaration(flags, td.simpleName(), "enum", null,
                td.interfaceNames(), td.typeParameters(), members);
    }

    /**
     * 通过解析 {@code <clinit>} 字节码提取每个枚举常量的构造器参数.
     *
     * @return 字段名到参数源文本的映射(例如 {@code "(1)"} 或 {@code "(\"foo\", 42)"})
     */
    private Map<String, String> extractConstantArgs(TypeDeclaration td,
                                                    DecompileContext ctx) {
        Map<String, String> result = new HashMap<>();
        ClassFileModel cfm = ctx.classFile();
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

            // 前两个参数为合成参数(name 字符串和 ordinal 整数),用户参数从索引 2 开始
            if (argTexts.size() > 2 && fieldName != null) {
                List<String> userArgs = argTexts.subList(2, argTexts.size());
                result.put(fieldName, "(" + String.join(", ", userArgs) + ")");
            }

            i = j + 1; // 跳过已处理的指令序列
        }

        return result;
    }

    /** 检查字段是否为枚举常量(public static final 且类型与枚举类型相同) */
    private boolean isEnumConstantField(FieldDeclaration fd, String enumName) {
        int flags = fd.accessFlags();
        boolean isPublicStaticFinal = (flags & 0x0019) == 0x0019; // public static final
        if (!isPublicStaticFinal) {
            return false;
        }
        String typeStr = fd.type() != null ? fd.type().displayName() : "";
        return typeStr.contains(enumName);
    }

    /** 判断类型声明是否为枚举类(检查 ACC_ENUM 标志位) */
    private boolean isEnum(TypeDeclaration td) {
        return (td.accessFlags() & ACC_ENUM) != 0;
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

        Statement body = md.body() != null ? cleanEnumConstructorBody(md.body()) : null;

        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                newNames, newTypes, md.typeParameters(), body);
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
     * 为枚举常量构建匿名类体源文本,通过加载并反编译其内部类来实现
     * (例如序数 0 对应 InnerClass {@code EnumDemo$1}).
     */
    private String buildAnonymousClassBody(String constName, String args,
                                           String enumName, int ordinal,
                                           DecompileContext ctx) {
        String innerName = enumName + "$" + (ordinal + 1);

        // 根据枚举所在包名构建完整内部类名称
        ClassFileModel cfm = ctx.classFile();
        String pkg = cfm != null ? packageOf(cfm.internalName()) : "";
        String internalName = pkg.isEmpty() ? innerName : pkg + "/" + innerName;

        byte[] bytes = ctx.loadClassBytes(internalName);
        if (bytes == null) {
            return "";
        }

        try {
            ClassFileModel inner = new ClassFileReader().read(internalName, bytes);
            String bodies = decompileInnerClassMethods(inner, ctx);
            return bodies;
        } catch (IOException e) {
            return "";
        }
    }
}
