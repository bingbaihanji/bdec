package com.bingbaihanji.bdec.bytecode.model.constantpool;

/**
 * 常量池条目接口(密封接口).
 *
 * <p>定义 JVM 常量池中所有可能的条目类型(共计 17 种).
 * 每种常量类型由对应的内部 {@code record} 实现,提供类型安全的数据载体.
 *
 * <p>支持的常量池条目类型:
 * <ul>
 *   <li>{@link CpUtf8}              — UTF-8 字符串常量(tag=1)</li>
 *   <li>{@link CpInteger}           — 整型常量(tag=3)</li>
 *   <li>{@link CpFloat}             — 浮点常量(tag=4)</li>
 *   <li>{@link CpLong}              — 长整型常量(tag=5,占用两个索引位)</li>
 *   <li>{@link CpDouble}            — 双精度浮点常量(tag=6,占用两个索引位)</li>
 *   <li>{@link CpClass}             — 类引用(tag=7)</li>
 *   <li>{@link CpString}            — 字符串引用(tag=8)</li>
 *   <li>{@link CpFieldRef}          — 字段引用(tag=9)</li>
 *   <li>{@link CpMethodRef}         — 方法引用(tag=10)</li>
 *   <li>{@link CpInterfaceMethodRef}— 接口方法引用(tag=11)</li>
 *   <li>{@link CpNameAndType}       — 名称和类型描述符(tag=12)</li>
 *   <li>{@link CpMethodHandle}      — 方法句柄(tag=15)</li>
 *   <li>{@link CpMethodType}        — 方法类型描述符(tag=16)</li>
 *   <li>{@link CpDynamic}           — 动态常量(tag=17)</li>
 *   <li>{@link CpInvokeDynamic}     — 动态调用点(tag=18)</li>
 *   <li>{@link CpModule}            — 模块引用(tag=19)</li>
 *   <li>{@link CpPackage}           — 包引用(tag=20)</li>
 * </ul>
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.4">JVM Spec 4.4</a>
 */
public sealed interface ConstantPoolEntry
        permits
        ConstantPoolEntry.CpUtf8,
        ConstantPoolEntry.CpInteger,
        ConstantPoolEntry.CpFloat,
        ConstantPoolEntry.CpLong,
        ConstantPoolEntry.CpDouble,
        ConstantPoolEntry.CpClass,
        ConstantPoolEntry.CpString,
        ConstantPoolEntry.CpFieldRef,
        ConstantPoolEntry.CpMethodRef,
        ConstantPoolEntry.CpInterfaceMethodRef,
        ConstantPoolEntry.CpNameAndType,
        ConstantPoolEntry.CpMethodHandle,
        ConstantPoolEntry.CpMethodType,
        ConstantPoolEntry.CpDynamic,
        ConstantPoolEntry.CpInvokeDynamic,
        ConstantPoolEntry.CpModule,
        ConstantPoolEntry.CpPackage {

    /**
     * 返回该常量池条目的标签值.
     *
     * @return JVM 规范定义的常量池标签(1-20)
     */
    int tag();

    /** UTF-8 字符串常量(tag=1). */
    record CpUtf8(String value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 1;}
    }

    /** 整型常量(tag=3),对应 {@code CONSTANT_Integer_info}. */
    record CpInteger(int value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 3;}
    }

    /** 单精度浮点常量(tag=4),对应 {@code CONSTANT_Float_info}. */
    record CpFloat(float value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 4;}
    }

    /** 长整型常量(tag=5),对应 {@code CONSTANT_Long_info},占用常量池两个索引位. */
    record CpLong(long value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 5;}
    }

    /** 双精度浮点常量(tag=6),对应 {@code CONSTANT_Double_info},占用常量池两个索引位. */
    record CpDouble(double value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 6;}
    }

    /** 类引用常量(tag=7),指向一个 UTF-8 常量表示类的内部名称. */
    record CpClass(int nameIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 7;}
    }

    /** 字符串引用常量(tag=8),指向一个 UTF-8 常量表示字符串字面量. */
    record CpString(int stringIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 8;}
    }

    /** 字段引用常量(tag=9),包含类索引和名称与类型索引. */
    record CpFieldRef(int classIndex,

                      int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 9;}
    }

    /** 方法引用常量(tag=10),包含类索引和名称与类型索引. */
    record CpMethodRef(int classIndex,

                       int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 10;}
    }

    /** 接口方法引用常量(tag=11),包含类索引和名称与类型索引. */
    record CpInterfaceMethodRef(int classIndex,

                                int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 11;}
    }

    /** 名称与类型描述符常量(tag=12),包含名称索引和描述符索引. */
    record CpNameAndType(int nameIndex,

                         int descriptorIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 12;}
    }

    /** 方法句柄常量(tag=15),包含引用种类索引和引用索引. */
    record CpMethodHandle(int referenceKind,

                          int referenceIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 15;}
    }

    /** 方法类型描述符常量(tag=16),包含一个描述符索引. */
    record CpMethodType(int descriptorIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 16;}
    }

    /** 动态常量(tag=17),包含引导方法属性索引和名称与类型索引. */
    record CpDynamic(int bootstrapMethodAttrIndex,

                     int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 17;}
    }

    /** 动态调用点常量(tag=18),包含引导方法属性索引和名称与类型索引. */
    record CpInvokeDynamic(int bootstrapMethodAttrIndex,

                           int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 18;}
    }

    /** 模块引用常量(tag=19),指向一个 UTF-8 常量表示模块名称. */
    record CpModule(int nameIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 19;}
    }

    /** 包引用常量(tag=20),指向一个 UTF-8 常量表示包名称. */
    record CpPackage(int nameIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 20;}
    }
}
