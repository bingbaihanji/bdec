package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.*;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * 常量池解析器.
 *
 * <p>负责将类文件中的常量池二进制数据解析为 {@link ConstantPoolEntry} 对象数组.
 * 同时提供方便的静态辅助方法用于从常量池中提取 UTF-8 字符串和类名.
 *
 * <p>常量池的索引从 1 开始(索引 0 为无效占位),其中 {@code CONSTANT_Long_info}
 * 和 {@code CONSTANT_Double_info} 各占用两个索引位.
 */
public final class ConstantPoolParser {

    /**
     * 从常量池指定索引处提取 UTF-8 字符串.
     *
     * @param pool  常量池数组
     * @param index 常量池索引
     * @return 对应的 UTF-8 字符串,若索引无效则返回 {@code "<invalid utf8>"}
     */
    public static String utf8(ConstantPoolEntry[] pool, int index) {
        if (index <= 0 || index >= pool.length || !(pool[index] instanceof CpUtf8)) {
            return "<invalid utf8>";
        }
        return ((CpUtf8) pool[index]).value();
    }

    /**
     * 从常量池指定索引处提取类名.
     *
     * <p>从 {@code CONSTANT_Class_info} 条目中间接查找类名 UTF-8 字符串.
     *
     * @param pool      常量池数组
     * @param classIndex 指向 {@code CONSTANT_Class_info} 的索引
     * @return 类的内部名称,若索引无效则返回 {@code "<invalid class>"}
     */
    public static String className(ConstantPoolEntry[] pool, int classIndex) {
        if (classIndex <= 0 || classIndex >= pool.length || !(pool[classIndex] instanceof CpClass c)) {
            return "<invalid class>";
        }
        return utf8(pool, c.nameIndex());
    }

    /**
     * 解析常量池.
     *
     * <p>从数据流中读取常量池条目数量,然后依次解析每个条目的类型标签和内容.
     * 对于 {@code Long} 和 {@code Double} 类型的常量,跳过一个额外的索引位
     * 以符合 JVM 规范.
     *
     * @param in 指向常量池起始位置的数据输入流
     * @return 常量池条目数组(索引 0 为 {@code null} 占位符)
     * @throws IOException 如果读取过程中发生 I/O 错误或遇到未知的常量池标签
     */
    public ConstantPoolEntry[] parse(DataInputStream in) throws IOException {
        int cpCount = in.readUnsignedShort();
        ConstantPoolEntry[] pool = new ConstantPoolEntry[cpCount];

        int i = 1;
        while (i < cpCount) {
            int tag = in.readUnsignedByte();
            ConstantPoolEntry entry = switch (tag) {
                case 1 -> new CpUtf8(in.readUTF());
                case 3 -> new CpInteger(in.readInt());
                case 4 -> new CpFloat(in.readFloat());
                case 5 -> new CpLong(in.readLong());
                case 6 -> new CpDouble(in.readDouble());
                case 7 -> new CpClass(in.readUnsignedShort());
                case 8 -> new CpString(in.readUnsignedShort());
                case 9 -> new CpFieldRef(in.readUnsignedShort(), in.readUnsignedShort());
                case 10 -> new CpMethodRef(in.readUnsignedShort(), in.readUnsignedShort());
                case 11 -> new CpInterfaceMethodRef(in.readUnsignedShort(), in.readUnsignedShort());
                case 12 -> new CpNameAndType(in.readUnsignedShort(), in.readUnsignedShort());
                case 15 -> new CpMethodHandle(in.readUnsignedByte(), in.readUnsignedShort());
                case 16 -> new CpMethodType(in.readUnsignedShort());
                case 17 -> new CpDynamic(in.readUnsignedShort(), in.readUnsignedShort());
                case 18 -> new CpInvokeDynamic(in.readUnsignedShort(), in.readUnsignedShort());
                case 19 -> new CpModule(in.readUnsignedShort());
                case 20 -> new CpPackage(in.readUnsignedShort());
                default -> throw new IOException("Unknown constant pool tag: " + tag + " at index " + i);
            };
            pool[i] = entry;
            // Long 和 Double 类型常量占用两个索引位
            if (tag == 5 || tag == 6) {
                i += 2;
            } else {
                i++;
            }
        }
        return pool;
    }
}
