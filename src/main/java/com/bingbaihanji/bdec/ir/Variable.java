package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

/**
 * 变量.
 * <p>
 * 表示IR中的局部变量.每个变量由槽位(slot)和版本号(version)唯一标识.
 * 槽位对应JVM局部变量表中的索引,版本号用于SSA形式的唯一命名.
 * 变量记录其是否为方法参数以及原始索引,支持名称设置.
 * </p>
 */
public final class Variable implements Value {

    /** 局部变量槽位索引 */
    private final int slot;

    /** SSA版本号,同槽位每次赋值递增 */
    private final int version;

    /** 变量的Java类型 */
    private final JavaType type;

    /** 是否为方法参数 */
    private final boolean isParameter;

    /** 原始索引,用于生成默认名称 */
    private final int originalIndex;

    /** 变量名称(可为null,则使用默认名称"varN") */
    private String name;

    /** 泛型类型(来自 LocalVariableTypeTable,如 List<String>),可为 null */
    private JavaType genericType;

    /** JSR-308 类型注解(局部变量上的 RuntimeVisibleTypeAnnotations 0x40/0x41 条目) */
    private java.util.List<com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry> typeAnnotations
            = java.util.List.of();

    /**
     * 构造一个变量.
     *
     * @param slot          局部变量槽位索引
     * @param version       SSA版本号
     * @param type          Java类型
     * @param isParameter   是否为方法参数
     * @param originalIndex 原始索引
     */
    public Variable(int slot, int version, JavaType type, boolean isParameter, int originalIndex) {
        this.slot = slot;
        this.version = version;
        this.type = type;
        this.isParameter = isParameter;
        this.originalIndex = originalIndex;
    }

    /** @return 局部变量槽位索引 */
    public int slot() {return slot;}

    /** @return SSA版本号 */
    public int version() {return version;}

    /**
     * 获取变量名称.
     *
     * @return 变量名称,若未设置则返回默认名称"varN"
     */
    public String name() {return name != null ? name : "var" + originalIndex;}

    /** 设置变量名称 */
    public void setName(String name) {this.name = name;}

    /** @return 泛型类型(可为 null,回退到擦除类型) */
    public JavaType genericType() {return genericType;}

    /** 设置泛型类型(LVTT 签名解析结果) */
    public void setGenericType(JavaType t) {this.genericType = t;}

    /** @return 局部变量上的 JSR-308 类型注解条目 */
    public java.util.List<com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry> typeAnnotations() {
        return typeAnnotations;
    }

    /** 设置局部变量上的 JSR-308 类型注解条目 */
    public void setTypeAnnotations(
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry> anns) {
        this.typeAnnotations = anns != null ? anns : java.util.List.of();
    }

    @Override
    public JavaType type() {return type;}

    /** @return 是否为方法参数 */
    public boolean isParameter() {return isParameter;}

    /** @return 原始索引 */
    public int originalIndex() {return originalIndex;}

    @Override
    public String toString() {return name() + "(v" + version + ")";}

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Variable that)) {
            return false;
        }
        return slot == that.slot && version == that.version;
    }

    @Override
    public int hashCode() {return slot * 31 + version;}
}
