package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ExceptionHandlerModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.LocalVariableEntry;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Code 属性解析器(里程碑 Phase 3).
 *
 * <p>解析方法的 {@code Code} 属性(JVMS 4.7.3):字节码指令、异常处理器表,
 * 以及 Code 的子属性(LocalVariableTable、LocalVariableTypeTable、
 * RuntimeVisibleTypeAnnotations)。由 {@link StructureParser#parseMethods} 复用。</p>
 */
final class CodeAttributeParser {

    /** 指令解码器,用于解析 {@code Code} 属性中的字节码指令. */
    private final InstructionDecoder insnDecoder = new InstructionDecoder();

    /** 注解解析器,用于解析 Code 内的类型注解. */
    private final AnnotationParser annotationParser = new AnnotationParser();

    /**
     * Code 属性解析结果(方法体一次最多一个 Code 属性).
     *
     * @param maxStack     操作数栈最大深度
     * @param maxLocals    局部变量表槽位数
     * @param instructions 字节码指令列表
     * @param handlers     异常处理器表
     * @param localVarNames 槽位 → 局部变量名映射(最后出现的名称优先)
     * @param lvtEntries   局部变量表条目(作用域感知)
     * @param codeTypeAnns Code 内的类型注解
     */
    record CodeAttribute(
            int maxStack,
            int maxLocals,
            List<Instruction> instructions,
            List<ExceptionHandlerModel> handlers,
            Map<Integer, String> localVarNames,
            List<LocalVariableEntry> lvtEntries,
            List<TypeAnnotationEntry> codeTypeAnns) {
    }

    /**
     * 解析 Code 属性主体(调用方已读入 attribute_name_index 与 attribute_length).
     *
     * @param in   数据输入流,定位在 Code 属性数据起始位置
     * @param pool 已解析的常量池
     * @return Code 属性解析结果
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    CodeAttribute parseCode(DataInputStream in, ConstantPoolEntry[] pool)
            throws IOException {
        int maxStack = in.readUnsignedShort();
        int maxLocals = in.readUnsignedShort();
        int codeLength = in.readInt();
        byte[] code = new byte[codeLength];
        in.readFully(code);
        List<Instruction> instructions = insnDecoder.decodeAll(code, 0, codeLength);

        // 解析异常处理器表
        int excCount = in.readUnsignedShort();
        List<ExceptionHandlerModel> handlers = new ArrayList<>();
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

        Map<Integer, String> localVarNames = new HashMap<>();
        List<LocalVariableEntry> lvtEntries = new ArrayList<>();
        List<TypeAnnotationEntry> codeTypeAnns = new ArrayList<>();

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
                        localVarNames.put(index, varName); // 最后出现的名称优先,作用域感知查找处理消歧
                        lvtEntries.add(new LocalVariableEntry(startPc, length, varName,
                                index, varDesc, null));
                    }
                }
            } else if ("LocalVariableTypeTable".equals(subAttrName)) {
                // 泛型局部变量表:条目结构同 LVT,描述符为泛型签名
                int lvttLen = in.readUnsignedShort();
                for (int l = 0; l < lvttLen; l++) {
                    int startPc = in.readUnsignedShort();
                    int length = in.readUnsignedShort();
                    int lvttNameIdx = in.readUnsignedShort();
                    int lvttSigIdx = in.readUnsignedShort();
                    int index = in.readUnsignedShort();
                    String varName = ConstantPoolParser.utf8(pool, lvttNameIdx);
                    String varSig = ConstantPoolParser.utf8(pool, lvttSigIdx);
                    if (varName != null && !varName.isEmpty()
                            && varSig != null && varSig.contains("<")) {
                        // 仅保留含泛型参数的条目;与 LVT 条目按
                        // (startPc, slot) 对齐后合并签名
                        for (int ei = 0; ei < lvtEntries.size(); ei++) {
                            var e = lvtEntries.get(ei);
                            if (e.startPc() == startPc && e.slot() == index) {
                                lvtEntries.set(ei,
                                        new LocalVariableEntry(
                                                e.startPc(), e.length(), e.name(),
                                                e.slot(), e.typeDesc(), varSig));
                                break;
                            }
                        }
                    }
                }
            } else if ("RuntimeVisibleTypeAnnotations".equals(subAttrName)) {
                // Code 内的类型注解(局部变量 0x40/0x41、cast/new 等
                // 偏移量相关目标)——合并到方法级类型注解列表
                List<TypeAnnotationEntry> codeAnns = annotationParser.parseTypeAnnotations(in, pool);
                if (codeAnns != null && !codeAnns.isEmpty()) {
                    codeTypeAnns.addAll(codeAnns);
                }
            } else {
                in.skipBytes(len);
            }
        }

        return new CodeAttribute(maxStack, maxLocals, instructions, handlers,
                localVarNames, lvtEntries, codeTypeAnns);
    }
}
