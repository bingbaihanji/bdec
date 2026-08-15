package com.bingbaihanji.bdec.bytecode.model.constantpool;

import com.bingbaihanji.bdec.bytecode.model.AnnotationEntry;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;

import java.util.List;

/**
 * 记录组件条目.
 *
 * <p>表示类文件中 {@code Record} 属性(Java 16+ 引入)的一个组件.
 * 每个组件对应 {@code record} 类声明中的一个字段,包含组件名称和类型描述符,
 * 以及组件上的注解(可见/不可见)与类型注解——javac 把源码里组件声明上的注解
 * 写入 Record 属性组件属性,而不全部复制到 backing 字段声明注解(目标不含
 * FIELD 的注解如 @Target(RECORD_COMPONENT) 仅在此),故组件注解须以本条目为准.</p>
 *
 * @param name          组件名称(从常量池 UTF-8 条目解析得到)
 * @param descriptor    组件类型描述符(如 {@code "Ljava/lang/String;"})
 * @param signature     组件泛型签名(如 {@code Ljava/util/List<Ljava/lang/String;>;}),无则为空字符串
 * @param annotations   组件声明注解(可见/不可见合并),无则为空列表
 * @param typeAnnotations 组件类型上的 JSR-308 类型注解(可见/不可见合并),无则为空列表
 */
public record RecordComponentEntry(
        String name,
        String descriptor,
        String signature,
        List<AnnotationEntry> annotations,
        List<TypeAnnotationEntry> typeAnnotations
) {

    /** 旧式三参兼容构造:signature 空,注解空(与解析前的调用点一致). */
    public RecordComponentEntry(String name, String descriptor) {
        this(name, descriptor, "", List.of(), List.of());
    }
}
