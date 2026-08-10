package com.bingbaihanji.bdec.bytecode.model.constantpool;

/**
 * 内部类条目.
 *
 * <p>表示类文件中 {@code InnerClasses} 属性的一条记录.
 * 描述一个内部类(或内部接口,内部枚举,内部记录类)与其外部类,简单名称和访问标志的关系.
 *
 * @param innerClass  内部类的内部名称(以斜杠分隔,如 {@code pkg/Outer$Inner})
 * @param outerClass  外部类的内部名称,若为非嵌套类则为 {@code null}
 * @param simpleName  内部类的简单名称(如 {@code "Inner"}),若为匿名类则为 {@code null}
 * @param accessFlags 内部类的访问标志({@code ACC_PUBLIC},{@code ACC_STATIC} 等)
 */
public record InnerClassEntry(
        String innerClass,
        String outerClass,
        String simpleName,
        int accessFlags
) {}
