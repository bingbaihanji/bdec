package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.AnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.TypePathElement;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpDouble;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpFloat;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpInteger;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpLong;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 注解解析器(里程碑 Phase 3).
 *
 * <p>负责解析类文件中的注解相关结构:element_value,annotation,
 * RuntimeVisibleAnnotations 与 RuntimeVisibleTypeAnnotations 属性.
 * 由 {@link StructureParser} 在解析字段/方法/Code 属性时复用.</p>
 */
final class AnnotationParser {

    /**
     * 解析一个 element_value(JVMS 4.7.16.1).
     *
     * <p>tag 决定值的类型:'B','C','D','F','I','J','S','Z' 基本类型,
     * 's' 字符串,'e' 枚举,'c' 类字面量,'@' 嵌套注解,'[' 数组.</p>
     *
     * @param in   数据输入流
     * @param pool 常量池
     * @return 类型化值(String/Integer/Boolean/Character/Long/Float/Double/
     *         EnumValue/ClassValue/AnnotationEntry/List)
     */
    Object parseElementValue(DataInputStream in, ConstantPoolEntry[] pool)
            throws IOException {
        int tag = in.readUnsignedByte();
        return switch (tag) {
            case 'B', 'I', 'S' -> {
                int idx = in.readUnsignedShort();
                yield switch (pool[idx]) {
                    case CpInteger ci -> ci.value();
                    default -> 0;
                };
            }
            case 'C' -> {
                int idx = in.readUnsignedShort();
                yield switch (pool[idx]) {
                    case CpInteger ci -> (char) ci.value();
                    default -> '\0';
                };
            }
            case 'Z' -> {
                int idx = in.readUnsignedShort();
                yield switch (pool[idx]) {
                    case CpInteger ci -> ci.value() != 0;
                    default -> false;
                };
            }
            case 'J' -> {
                int idx = in.readUnsignedShort();
                yield switch (pool[idx]) {
                    case CpLong cl -> cl.value();
                    default -> 0L;
                };
            }
            case 'F' -> {
                int idx = in.readUnsignedShort();
                yield switch (pool[idx]) {
                    case CpFloat cf -> cf.value();
                    default -> 0.0f;
                };
            }
            case 'D' -> {
                int idx = in.readUnsignedShort();
                yield switch (pool[idx]) {
                    case CpDouble cd -> cd.value();
                    default -> 0.0d;
                };
            }
            case 's' -> {
                // JVMS 4.7.16.1:'s' 的 const_value_index 直接指向
                // CONSTANT_Utf8 条目(非 CONSTANT_String).
                int idx = in.readUnsignedShort();
                yield ConstantPoolParser.utf8(pool, idx);
            }
            case 'e' -> {
                int typeIdx = in.readUnsignedShort();
                int nameIdx = in.readUnsignedShort();
                yield new AnnotationEntry.EnumValue(
                        stripTypeDescriptor(ConstantPoolParser.utf8(pool, typeIdx)),
                        ConstantPoolParser.utf8(pool, nameIdx));
            }
            case 'c' -> {
                int clsIdx = in.readUnsignedShort();
                yield new AnnotationEntry.ClassValue(
                        stripTypeDescriptor(ConstantPoolParser.utf8(pool, clsIdx)));
            }
            case '@' -> parseAnnotation(in, pool);
            case '[' -> {
                int n = in.readUnsignedShort();
                List<Object> arr = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    arr.add(parseElementValue(in, pool));
                }
                yield arr;
            }
            default -> "<unknown>";
        };
    }

    /** 类型描述符(Ljava/lang/String;)→ 内部名(java/lang/String) */
    private String stripTypeDescriptor(String desc) {
        if (desc == null) {
            return null;
        }
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1);
        }
        return desc;
    }

    /**
     * 解析一个 annotation(JVMS 4.7.16):类型描述符 + 元素名值对.
     */
    AnnotationEntry parseAnnotation(
            DataInputStream in, ConstantPoolEntry[] pool) throws IOException {
        int typeIdx = in.readUnsignedShort();
        String typeDesc = ConstantPoolParser.utf8(pool, typeIdx);
        String internalName = typeDesc != null && typeDesc.startsWith("L")
                && typeDesc.endsWith(";")
                ? typeDesc.substring(1, typeDesc.length() - 1) : typeDesc;
        int n = in.readUnsignedShort();
        List<AnnotationEntry.ElementPair> pairs = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int nameIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            Object value = parseElementValue(in, pool);
            pairs.add(new AnnotationEntry.ElementPair(name, value));
        }
        return new AnnotationEntry(internalName, pairs);
    }

    /**
     * 解析 RuntimeVisibleAnnotations 属性(num_annotations 后的注解列表).
     */
    List<AnnotationEntry> parseAnnotations(
            DataInputStream in, ConstantPoolEntry[] pool) throws IOException {
        int n = in.readUnsignedShort();
        List<AnnotationEntry> result = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            result.add(parseAnnotation(in, pool));
        }
        return result;
    }

    /**
     * 解析 RuntimeVisibleTypeAnnotations 属性(JVMS 4.7.20):类型注解列表.
     * 每个条目由目标类型,目标信息,类型路径和注解实例组成.
     */
    List<TypeAnnotationEntry> parseTypeAnnotations(
            DataInputStream in, ConstantPoolEntry[] pool) throws IOException {
        int n = in.readUnsignedShort();
        List<TypeAnnotationEntry> result = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int targetType = in.readUnsignedByte();
            int[] targetInfo = parseTargetInfo(in, targetType);
            int pathLen = in.readUnsignedByte();
            List<TypePathElement> path = new ArrayList<>();
            for (int p = 0; p < pathLen; p++) {
                int kind = in.readUnsignedByte();
                int argIdx = in.readUnsignedByte();
                path.add(new TypePathElement(kind, argIdx));
            }
            var annotation = parseAnnotation(in, pool);
            result.add(new TypeAnnotationEntry(
                    targetType, targetInfo, path, annotation));
        }
        return result;
    }

    /**
     * 解析类型注解的 target_info(JVMS 4.7.20.1),结构随 target_type 而异.
     * 以 int 数组存储原始数据,含义见 {@code TypeAnnotationEntry} 文档.
     */
    private int[] parseTargetInfo(DataInputStream in, int targetType) throws IOException {
        return switch (targetType) {
            case 0x00, 0x01 -> new int[]{in.readUnsignedByte()};          // 类型参数索引
            case 0x10 -> new int[]{in.readUnsignedShort()};               // 父类型索引
            case 0x11, 0x12 -> new int[]{in.readUnsignedByte(), in.readUnsignedByte()}; // 参数+边界
            case 0x13, 0x14, 0x15 -> new int[0];                          // 字段/返回/接收者
            case 0x16 -> new int[]{in.readUnsignedByte()};                // 形式参数索引
            case 0x17 -> new int[]{in.readUnsignedShort()};               // throws 索引
            case 0x40, 0x41 -> {                                           // 局部变量/资源变量表
                int len = in.readUnsignedShort();
                int[] tbl = new int[1 + len * 3];
                tbl[0] = len;
                for (int i = 0; i < len; i++) {
                    tbl[1 + i * 3] = in.readUnsignedShort(); // start_pc
                    tbl[2 + i * 3] = in.readUnsignedShort(); // length
                    tbl[3 + i * 3] = in.readUnsignedShort(); // index
                }
                yield tbl;
            }
            case 0x42 -> new int[]{in.readUnsignedShort()};               // 异常表索引
            case 0x43, 0x44, 0x45, 0x46 -> new int[]{in.readUnsignedShort()}; // 偏移量
            case 0x47, 0x48, 0x49, 0x4A, 0x4B -> new int[]{in.readUnsignedShort(), in.readUnsignedByte()}; // 偏移量+类型参数
            default -> new int[0];
        };
    }
}
