package com.bingbaihanji.bdec.bytecode.model;

import java.util.List;

/**
 * JSR-308 类型注解条目——来自 RuntimeVisibleTypeAnnotations 属性(JVMS 4.7.20).
 *
 * <p>与普通注解不同,类型注解携带目标类型(target_type)、目标信息(target_info)
 * 和类型路径(type_path),用于定位注解在类型结构中的准确位置,例如
 * {@code List<@NonNull String>} 中 {@code @NonNull} 的路径为 {@code [TYPE_ARGUMENT(0)]}.</p>
 *
 * <p>target_info 以 int 数组存储原始数据,内容随 target_type 而异:</p>
 * <ul>
 *   <li>0x16(形式参数)/0x00/0x01(类型参数):[index]</li>
 *   <li>0x17(throws):[throws_type_index]</li>
 *   <li>0x13/0x14/0x15(字段/返回/接收者类型):空</li>
 *   <li>0x40/0x41(局部变量/资源变量):[n, start_pc0, length0, index0, ...]</li>
 *   <li>其余(偏移量类):[offset] 或 [offset, type_argument_index]</li>
 * </ul>
 *
 * @param targetType 注解目标类型(0x00-0x4B)
 * @param targetInfo 目标信息原始数据
 * @param typePath   类型路径(注解在类型树中的位置)
 * @param annotation 注解实例
 */
public record TypeAnnotationEntry(
        int targetType,
        int[] targetInfo,
        List<TypePathElement> typePath,
        AnnotationEntry annotation
) {

    /** 字段类型(0x13) */
    public static final int TARGET_FIELD = 0x13;
    /** 方法返回类型(0x14) */
    public static final int TARGET_METHOD_RETURN = 0x14;
    /** 接收者类型(0x15) */
    public static final int TARGET_METHOD_RECEIVER = 0x15;
    /** 形式参数类型(0x16) */
    public static final int TARGET_FORMAL_PARAMETER = 0x16;
    /** throws 子句(0x17) */
    public static final int TARGET_THROWS = 0x17;
    /** 局部变量类型(0x40) */
    public static final int TARGET_LOCAL_VARIABLE = 0x40;
    /** instanceof 表达式中的类型(0x43):target_info = [offset] */
    public static final int TARGET_INSTANCEOF = 0x43;
    /** 对象创建表达式中的类型(0x44,含数组创建):target_info = [offset] */
    public static final int TARGET_NEW = 0x44;
    /** cast 表达式中的类型(0x47):target_info = [offset, type_argument_index] */
    public static final int TARGET_CAST = 0x47;
}
