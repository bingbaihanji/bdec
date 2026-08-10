package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.RecordComponentEntry;

import java.util.Collections;
import java.util.List;

/**
 * 类文件模型.
 *
 * <p>封装一个 Java 类文件({@code .class})的完整结构信息,包括:
 * <ul>
 *   <li>版本号(主版本号与次版本号)</li>
 *   <li>访问标志</li>
 *   <li>类名,父类名,实现的接口列表</li>
 *   <li>字段列表与方法列表</li>
 *   <li>常量池</li>
 *   <li>泛型签名,引导方法,记录组件,允许的子类,内部类等属性</li>
 * </ul>
 *
 * <p>该类设计为 Java {@code record},天然不可变,适用于反编译过程中的数据传递.
 *
 * @param majorVersion          主版本号(例如 Java 8 为 52,Java 21 为 65)
 * @param minorVersion          次版本号(通常为 0)
 * @param accessFlags           类访问标志({@code ACC_PUBLIC},{@code ACC_FINAL} 等)
 * @param internalName          类的内部名称(以斜杠分隔,如 {@code java/lang/Object})
 * @param superInternalName     父类的内部名称,若为 {@code null} 则表示 {@code java.lang.Object}
 * @param interfaceInternalNames 直接实现的接口内部名称列表
 * @param fields                字段模型列表
 * @param methods               方法模型列表
 * @param constantPool          常量池条目数组(索引 0 为占位,实际从 1 开始)
 * @param signature             类级泛型签名属性,若无则为空字符串
 * @param bootstrapMethods      引导方法属性列表(Java 7+ 的 {@code invokedynamic} 支持)
 * @param recordComponents      记录组件属性列表(Java 16+ 的 {@code record} 类)
 * @param permittedSubclasses   密封类允许的子类列表(Java 17+ 的 {@code sealed class})
 * @param innerClasses          内部类属性列表
 */
public record ClassFileModel(
        int majorVersion,
        int minorVersion,
        int accessFlags,
        String internalName,
        String superInternalName,
        List<String> interfaceInternalNames,
        List<FieldModel> fields,
        List<MethodModel> methods,
        ConstantPoolEntry[] constantPool,
        String signature,
        List<BootstrapMethodEntry> bootstrapMethods,
        List<RecordComponentEntry> recordComponents,
        List<String> permittedSubclasses,
        List<InnerClassEntry> innerClasses
) {

    /** 向后兼容的构造函数,不含签名与引导方法信息. */
    public ClassFileModel(int majorVersion, int minorVersion, int accessFlags,
                          String internalName, String superInternalName,
                          List<String> interfaceInternalNames, List<FieldModel> fields,
                          List<MethodModel> methods, ConstantPoolEntry[] constantPool) {
        this(majorVersion, minorVersion, accessFlags, internalName, superInternalName,
                interfaceInternalNames, fields, methods, constantPool, "",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
    }

    /** 包含签名但不含引导方法的构造函数. */
    public ClassFileModel(int majorVersion, int minorVersion, int accessFlags,
                          String internalName, String superInternalName,
                          List<String> interfaceInternalNames, List<FieldModel> fields,
                          List<MethodModel> methods, ConstantPoolEntry[] constantPool,
                          String signature) {
        this(majorVersion, minorVersion, accessFlags, internalName, superInternalName,
                interfaceInternalNames, fields, methods, constantPool, signature,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
    }
}
