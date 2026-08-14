package com.bingbaihanji.bdec.bytecode.model;

/**
 * 局部变量表条目 — 包含作用域信息.
 *
 * <p>对应 class 文件 LocalVariableTable 属性中的单个条目.
 * 与简单的 {@code Map<Integer, String>} 不同,此记录保留了 startPc 和 length,
 * 允许按字节码偏移量进行作用域感知的变量名查找.
 *
 * <p>参考 Vineflower 的 {@code StructLocalVariableTableEntry}.
 *
 * @param startPc  变量作用域开始的字节码偏移量(含)
 * @param length   作用域长度(字节码偏移量单位)
 * @param name     局部变量名(来自 UTF8 常量池)
 * @param slot     局部变量槽位索引
 * @param typeDesc 变量类型描述符(如 {@code I}, {@code Ljava/lang/String;})
 * @param typeSignature 变量泛型签名(来自 LocalVariableTypeTable,
 *                      如 {@code Ljava/util/List<Ljava/lang/String;>;},无则 null)
 */
public record LocalVariableEntry(
        int startPc,
        int length,
        String name,
        int slot,
        String typeDesc,
        String typeSignature
) {

    /** 检查此条目在给定字节码偏移量处是否有效 */
    public boolean covers(int pc) {
        return pc >= startPc && pc < startPc + length;
    }

    /** 结束偏移量(不含) */
    public int endPc() {
        return startPc + length;
    }
}
