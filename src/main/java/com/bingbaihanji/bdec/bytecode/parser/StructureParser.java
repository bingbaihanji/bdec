package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.AnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.ExceptionHandlerModel;
import com.bingbaihanji.bdec.bytecode.model.FieldModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.LocalVariableEntry;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpDouble;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpFloat;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpInteger;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpLong;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpString;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeResolver;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构解析器(里程碑 Phase 3).
 *
 * <p>负责解析类文件中字段表({@code field_info})和方法表({@code method_info})
 * 的顶层循环.注解结构解析委托 {@link AnnotationParser},Code 属性解析委托
 * {@link CodeAttributeParser}.</p>
 *
 * <p>该类仅在同一包内可见(package-private),作为 {@link ClassFileReader} 的辅助组件.
 */
class StructureParser {

    /** 注解解析器(element_value / annotation / type-annotation). */
    private final AnnotationParser annotationParser = new AnnotationParser();

    /** Code 属性解析器(字节码指令 + LVT/LVTT + 类型注解). */
    private final CodeAttributeParser codeParser = new CodeAttributeParser();

    /**
     * 解析字段表.
     *
     * <p>遍历类文件中所有字段,提取每个字段的访问标志,名称,描述符,
     * 常量值(来自 {@code ConstantValue} 属性)和泛型签名.
     *
     * @param in    数据输入流,定位在字段表起始位置
     * @param pool  已解析的常量池
     * @param count 字段数量
     * @return 字段模型列表
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    List<FieldModel> parseFields(DataInputStream in, ConstantPoolEntry[] pool, int count)
            throws IOException {
        List<FieldModel> fields = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int accessFlags = in.readUnsignedShort();
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String desc = ConstantPoolParser.utf8(pool, descIdx);
            JavaType type = TypeResolver.parseFieldDescriptor(desc);

            Object constValue = null;
            String signature = "";
            List<AnnotationEntry> anns = List.of();
            List<TypeAnnotationEntry> typeAnns = List.of();
            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);
                switch (attrName) {
                    case "Signature" -> {
                        int sigIdx = in.readUnsignedShort();
                        signature = ConstantPoolParser.utf8(pool, sigIdx);
                    }
                    case "ConstantValue" -> {
                        int cvIdx = in.readUnsignedShort();
                        ConstantPoolEntry entry = pool[cvIdx];
                        constValue = switch (entry) {
                            case CpInteger ci -> ci.value();
                            case CpFloat cf -> cf.value();
                            case CpLong cl -> cl.value();
                            case CpDouble cd -> cd.value();
                            case CpString cs -> ConstantPoolParser.utf8(pool, cs.stringIndex());
                            default -> "<unknown constant>";
                        };
                    }
                    case "RuntimeVisibleAnnotations" -> anns = annotationParser.parseAnnotations(in, pool);
                    case "RuntimeVisibleTypeAnnotations" ->
                        // JSR-308 类型注解(字段类型上的注解)
                            typeAnns = annotationParser.parseTypeAnnotations(in, pool);
                    case null, default -> in.skipBytes(attrLen);
                }
            }
            fields.add(new FieldModel(accessFlags, name, type, constValue, signature, anns, typeAnns));
        }
        return fields;
    }

    /**
     * 解析方法表.
     *
     * <p>遍历类文件中所有方法,提取每个方法的访问标志,名称,描述符,
     * 返回类型与参数类型,字节码指令,异常处理器表,签名,局部变量表等信息.
     * {@code Code} 属性的解析委托 {@link CodeAttributeParser}.</p>
     *
     * @param in    数据输入流,定位在方法表起始位置
     * @param pool  已解析的常量池
     * @param count 方法数量
     * @return 方法模型列表
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    List<MethodModel> parseMethods(DataInputStream in, ConstantPoolEntry[] pool, int count)
            throws IOException {
        List<MethodModel> methods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int accessFlags = in.readUnsignedShort();
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String desc = ConstantPoolParser.utf8(pool, descIdx);

            JavaType[] paramTypes = TypeResolver.parseMethodParameterTypes(desc);
            JavaType returnType = TypeResolver.parseMethodReturnType(desc);

            List<Instruction> instructions = null;
            List<ExceptionHandlerModel> handlers = List.of();
            int maxStack = 0, maxLocals = 0;
            String signature = "";
            List<String> declaredExceptions = new ArrayList<>();
            Object annotationDefault = null;
            List<AnnotationEntry> anns = List.of();
            List<List<AnnotationEntry>> paramAnns = List.of();
            List<TypeAnnotationEntry> typeAnns = List.of();
            // Code 属性内的类型注解(局部变量 0x40/0x41 等偏移量相关目标)
            List<TypeAnnotationEntry> codeTypeAnns = new ArrayList<>();
            Map<Integer, String> localVarNames = new HashMap<>();
            List<LocalVariableEntry> lvtEntries = new ArrayList<>();

            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);

                switch (attrName) {
                    case "Signature" -> {
                        int sigIdx = in.readUnsignedShort();
                        signature = ConstantPoolParser.utf8(pool, sigIdx);
                    }
                    case "RuntimeVisibleAnnotations" ->
                        // 方法级注解
                            anns = annotationParser.parseAnnotations(in, pool);
                    case "RuntimeVisibleTypeAnnotations" ->
                        // JSR-308 类型注解(返回类型/参数类型/throws 上的注解)
                            typeAnns = annotationParser.parseTypeAnnotations(in, pool);
                    case "RuntimeVisibleParameterAnnotations" -> {
                        // 参数级注解(JVMS 4.7.18):num_parameters 个参数注解列表
                        int numParams = in.readUnsignedByte();
                        List<List<AnnotationEntry>> pAnns = new ArrayList<>();
                        for (int p = 0; p < numParams; p++) {
                            pAnns.add(annotationParser.parseAnnotations(in, pool));
                        }
                        paramAnns = pAnns;
                    }
                    case "AnnotationDefault" ->
                        // 注解方法元素的默认值(element_value)
                            annotationDefault = annotationParser.parseElementValue(in, pool);
                    case "Exceptions" -> {
                        // Exceptions 属性:方法声明中 throws 的受检异常列表
                        int n = in.readUnsignedShort();
                        for (int x = 0; x < n; x++) {
                            int classIdx = in.readUnsignedShort();
                            String internalName = ConstantPoolParser.className(pool, classIdx);
                            if (internalName != null) {
                                declaredExceptions.add(internalName);
                            }
                        }
                    }
                    case "Code" -> {
                        // Code 属性核心结构(指令,异常处理器表,局部变量表)
                        CodeAttributeParser.CodeAttribute codeAttr = codeParser.parseCode(in, pool);
                        maxStack = codeAttr.maxStack();
                        maxLocals = codeAttr.maxLocals();
                        instructions = codeAttr.instructions();
                        handlers = codeAttr.handlers();
                        localVarNames = codeAttr.localVarNames();
                        lvtEntries = codeAttr.lvtEntries();
                        codeTypeAnns = codeAttr.codeTypeAnns();
                    }
                    case null, default -> in.skipBytes(attrLen);
                }
            }
            // 合并方法级与 Code 级类型注解(局部变量注解位于 Code 属性内)
            if (!codeTypeAnns.isEmpty()) {
                List<TypeAnnotationEntry> merged = new ArrayList<>(typeAnns);
                merged.addAll(codeTypeAnns);
                typeAnns = merged;
            }
            methods.add(new MethodModel(accessFlags, name, desc, returnType, paramTypes,
                    instructions, handlers, maxStack, maxLocals, signature,
                    localVarNames, lvtEntries, declaredExceptions, annotationDefault, anns,
                    paramAnns, typeAnns));
        }
        return methods;
    }
}
