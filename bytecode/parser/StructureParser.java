package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ExceptionHandlerModel;
import com.bingbaihanji.bdec.bytecode.model.FieldModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
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
import java.util.List;

/**
 * 结构解析器.
 *
 * <p>负责解析类文件中字段表({@code field_info})和方法表({@code method_info})
 * 的结构化数据.每个字段和方法都包含:
 * <ul>
 *   <li>访问标志</li>
 *   <li>名称索引与描述符索引(指向常量池)</li>
 *   <li>属性表(如 {@code Signature},{@code ConstantValue},{@code Code} 等)</li>
 * </ul>
 *
 * <p>该类仅在同一包内可见(package-private),作为 {@link ClassFileReader} 的辅助组件.
 */
class StructureParser {

    /** 指令解码器,用于解析 {@code Code} 属性中的字节码指令. */
    private final InstructionDecoder insnDecoder = new InstructionDecoder();

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
            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);
                if ("Signature".equals(attrName)) {
                    int sigIdx = in.readUnsignedShort();
                    signature = ConstantPoolParser.utf8(pool, sigIdx);
                } else if ("ConstantValue".equals(attrName)) {
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
                } else {
                    in.skipBytes(attrLen);
                }
            }
            fields.add(new FieldModel(accessFlags, name, type, constValue, signature));
        }
        return fields;
    }

    /**
     * 解析方法表.
     *
     * <p>遍历类文件中所有方法,提取每个方法的访问标志,名称,描述符,
     * 返回类型与参数类型,字节码指令,异常处理器表,签名,局部变量表等信息.
     *
     * <p>{@code Code} 属性中包含的子属性也会被解析:
     * <ul>
     *   <li>{@code LocalVariableTable} — 局部变量表,用于还原参数名和局部变量名</li>
     * </ul>
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
            java.util.Map<Integer, String> localVarNames = new java.util.HashMap<>();
            java.util.List<com.bingbaihanji.bdec.bytecode.model.LocalVariableEntry> lvtEntries
                    = new java.util.ArrayList<>();

            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);

                if ("Signature".equals(attrName)) {
                    int sigIdx = in.readUnsignedShort();
                    signature = ConstantPoolParser.utf8(pool, sigIdx);
                } else if ("Code".equals(attrName)) {
                    // Code 属性核心结构
                    maxStack = in.readUnsignedShort();
                    maxLocals = in.readUnsignedShort();
                    int codeLength = in.readInt();
                    byte[] code = new byte[codeLength];
                    in.readFully(code);
                    instructions = insnDecoder.decodeAll(code, 0, codeLength);

                    // 解析异常处理器表
                    int excCount = in.readUnsignedShort();
                    handlers = new ArrayList<>();
                    for (int e = 0; e < excCount; e++) {
                        int startPc = in.readUnsignedShort();
                        int endPc = in.readUnsignedShort();
                        int handlerPc = in.readUnsignedShort();
                        int catchTypeIdx = in.readUnsignedShort();
                        // catchType 为 0 时表示 finally 块(捕获任意异常)
                        String catchType = catchTypeIdx == 0 ? null
                                : ConstantPoolParser.className(pool, catchTypeIdx);
                        handlers.add(new ExceptionHandlerModel(startPc, endPc, handlerPc, catchType));
                    }

                    // 解析 Code 子属性(行号表,局部变量表等)
                    int codeAttrCount = in.readUnsignedShort();
                    for (int ca = 0; ca < codeAttrCount; ca++) {
                        int subAttrNameIdx = in.readUnsignedShort();
                        int len = in.readInt();
                        String subAttrName = ConstantPoolParser.utf8(pool, subAttrNameIdx);
                        if ("LocalVariableTable".equals(subAttrName)) {
                            int lvtLen = in.readUnsignedShort();
                            for (int l = 0; l < lvtLen; l++) {
                                int startPc = in.readUnsignedShort();
                                int length = in.readUnsignedShort();
                                int lvtNameIdx = in.readUnsignedShort();
                                int lvtDescIdx = in.readUnsignedShort();
                                int index = in.readUnsignedShort();
                                String varName = ConstantPoolParser.utf8(pool, lvtNameIdx);
                                String varDesc = ConstantPoolParser.utf8(pool, lvtDescIdx);
                                // 存储作用域感知条目,用于按字节码偏移量查找
                                if (varName != null && !varName.isEmpty()) {
                                    localVarNames.putIfAbsent(index, varName);
                                    lvtEntries.add(new com.bingbaihanji.bdec.bytecode.model
                                            .LocalVariableEntry(startPc, length, varName,
                                            index, varDesc));
                                }
                            }
                        } else {
                            in.skipBytes(len);
                        }
                    }
                } else {
                    in.skipBytes(attrLen);
                }
            }
            methods.add(new MethodModel(accessFlags, name, desc, returnType, paramTypes,
                    instructions, handlers, maxStack, maxLocals, signature,
                    localVarNames, lvtEntries));
        }
        return methods;
    }
}
