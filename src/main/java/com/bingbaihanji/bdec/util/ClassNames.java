package com.bingbaihanji.bdec.util;

/**
 * 类名相关的字符串工具.
 *
 * <p>集中管理散落在各层的类名判定逻辑,避免同一定义在多处漂移.
 */
public final class ClassNames {

    private ClassNames() {
    }

    /**
     * 检查简单类名是否为匿名类(美元符后紧跟数字).
     *
     * <p>匿名类的内部名称形如 {@code Use$1},{@code Foo$2Local}——{@code $} 后紧跟
     * 数字.此类名称在 Java 源码中不可作为类型名引用,故各类型渲染路径需据此跳过
     * {@code $} → {@code .} 的转换与 import 收集.
     *
     * @param simpleName 内部名称的最后一段(不含包前缀),可为 {@code null}
     * @return 若为匿名类引用则返回 {@code true}
     */
    public static boolean isAnonymousClassName(String simpleName) {
        if (simpleName == null) {
            return false;
        }
        int idx = simpleName.lastIndexOf('$');
        if (idx >= 0 && idx + 1 < simpleName.length()) {
            char c = simpleName.charAt(idx + 1);
            return c >= '0' && c <= '9';
        }
        return false;
    }
}
