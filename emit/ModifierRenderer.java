package com.bingbaihanji.bdec.emit;

import com.bingbaihanji.bdec.bytecode.model.AccessFlags;

/**
 * 修饰符渲染器(里程碑 Phase 3).
 *
 * <p>统一输出类/接口,方法,字段的访问修饰符关键字
 * (public/private/protected/static/final/abstract 等),依据 JVM 规范的
 * ACC_* 常量进行位判断.消除 {@link SourceEmitter} 与
 * {@link StatementEmitter} 中三处近乎复制的修饰符渲染逻辑.</p>
 */
public final class ModifierRenderer {

    private ModifierRenderer() {
    }

    /**
     * 输出类/接口/枚举的修饰符关键字.
     *
     * @param flags       访问标志位掩码
     * @param isInterface 是否为接口(接口不输出 abstract 修饰符)
     * @param isEnum      是否为枚举(枚举不输出 final 修饰符)
     * @param w           缩进写入器
     */
    public static void emitClassModifiers(int flags, boolean isInterface,
                                          boolean isEnum, IndentWriter w) {
        if ((flags & AccessFlags.ACC_PUBLIC) != 0) {
            w.token("public").space();
        } else if ((flags & AccessFlags.ACC_PRIVATE) != 0) {
            w.token("private").space();
        } else if ((flags & AccessFlags.ACC_PROTECTED) != 0) {
            w.token("protected").space();
        }
        // 接口隐式为 abstract,不重复输出该关键字
        if ((flags & AccessFlags.ACC_ABSTRACT) != 0 && !isInterface) {
            w.token("abstract").space();
        }
        // 枚举隐式为 final,Java 语法不允许显式 final 修饰符(javac 报错).
        if ((flags & AccessFlags.ACC_FINAL) != 0 && !isEnum) {
            w.token("final").space();
        }
        // ACC_SUPER 在所有现代 class 文件中均设置,不应作为修饰符输出.
        // ACC_STATIC 仅适用于嵌套类.
        if ((flags & AccessFlags.ACC_STATIC) != 0) {
            w.token("static").space();
        }
    }

    /**
     * 输出方法的修饰符关键字(public/private/protected/static/final 等).
     *
     * @param flags       访问标志位掩码
     * @param isInterface 封闭类型是否为接口(影响 default 关键字输出)
     * @param w           缩进写入器
     */
    public static void emitMethodModifiers(int flags, boolean isInterface, IndentWriter w) {
        if ((flags & AccessFlags.ACC_PUBLIC) != 0) {
            w.token("public").space();
        } else if ((flags & AccessFlags.ACC_PRIVATE) != 0) {
            w.token("private").space();
        } else if ((flags & AccessFlags.ACC_PROTECTED) != 0) {
            w.token("protected").space();
        }
        // 接口中的非 abstract,非 static,非 private 方法需要 default 关键字
        if (isInterface && (flags & AccessFlags.ACC_ABSTRACT) == 0
                && (flags & AccessFlags.ACC_STATIC) == 0
                && (flags & AccessFlags.ACC_PRIVATE) == 0) {
            w.token("default").space();
        }
        if ((flags & AccessFlags.ACC_STATIC) != 0) {
            w.token("static").space();
        }
        if ((flags & AccessFlags.ACC_FINAL) != 0) {
            w.token("final").space();
        }
        if ((flags & AccessFlags.ACC_SYNCHRONIZED) != 0) {
            w.token("synchronized").space();
        }
        if ((flags & AccessFlags.ACC_NATIVE) != 0) {
            w.token("native").space();
        }
        if ((flags & AccessFlags.ACC_ABSTRACT) != 0) {
            w.token("abstract").space();
        }
    }

    /**
     * 输出字段的修饰符关键字(public/private/protected/static/final/volatile/transient).
     *
     * @param flags 访问标志位掩码
     * @param w     缩进写入器
     */
    public static void emitFieldModifiers(int flags, IndentWriter w) {
        if ((flags & AccessFlags.ACC_PUBLIC) != 0) {
            w.token("public").space();
        } else if ((flags & AccessFlags.ACC_PRIVATE) != 0) {
            w.token("private").space();
        } else if ((flags & AccessFlags.ACC_PROTECTED) != 0) {
            w.token("protected").space();
        }
        if ((flags & AccessFlags.ACC_STATIC) != 0) {
            w.token("static").space();
        }
        if ((flags & AccessFlags.ACC_FINAL) != 0) {
            w.token("final").space();
        }
        if ((flags & AccessFlags.ACC_VOLATILE) != 0) {
            w.token("volatile").space();
        }
        if ((flags & AccessFlags.ACC_TRANSIENT) != 0) {
            w.token("transient").space();
        }
    }
}
