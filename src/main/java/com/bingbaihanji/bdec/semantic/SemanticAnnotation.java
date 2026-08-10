package com.bingbaihanji.bdec.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语义注解,附加于 {@link com.bingbaihanji.bdec.ir.IrInstruction} 之上.
 *
 * <p>每条注解携带一个 {@link SemanticTag} 标签以及可选的带类型属性.
 * 属性以 {@code String → Object} 键值对形式存储以保持灵活性;
 * 键名应使用本类中定义的常量.
 */
public record SemanticAnnotation(
        SemanticTag tag,
        Map<String, Object> properties
) {

    // ── 常用属性键名常量 ──────────────────────────────────

    /** 布尔返回值(Boolean 类型),用于 BOOLEAN_RETURN 标签 */
    public static final String KEY_BOOLEAN_VALUE = "booleanValue";

    /** 构造函数委托调用的目标类名(String 类型) */
    public static final String KEY_TARGET_CLASS = "targetClass";

    /** synchronized 块的监视器对象变量名(String 类型) */
    public static final String KEY_MONITOR_OBJECT = "monitorObject";

    /** 消除前的原始方法名(String 类型) */
    public static final String KEY_ORIGINAL_METHOD = "originalMethod";

    /** 静态方法调用的声明类名(String 类型) */
    public static final String KEY_DECLARING_CLASS = "declaringClass";

    // ── 工厂方法 ─────────────────────────────────────────────────

    /** 创建仅含标签,不含属性的语义注解 */
    public static SemanticAnnotation of(SemanticTag tag) {
        return new SemanticAnnotation(tag, Collections.emptyMap());
    }

    /** 创建含单个属性的语义注解 */
    public static SemanticAnnotation of(SemanticTag tag, String key, Object value) {
        return new SemanticAnnotation(tag, Map.of(key, value));
    }

    /** 创建含两个属性的语义注解 */
    public static SemanticAnnotation of(SemanticTag tag,
                                        String k1, Object v1,
                                        String k2, Object v2) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(k1, v1);
        props.put(k2, v2);
        return new SemanticAnnotation(tag, Collections.unmodifiableMap(props));
    }

    /** 创建携带标签和完整属性映射的语义注解 */
    public static SemanticAnnotation of(SemanticTag tag, Map<String, Object> properties) {
        return new SemanticAnnotation(tag, Collections.unmodifiableMap(
                new LinkedHashMap<>(properties)));
    }

    /** 便捷方法:获取属性值,支持默认值回退 */
    public Object get(String key, Object defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /** 便捷方法:获取布尔类型的属性值 */
    public boolean getBoolean(String key) {
        Object v = properties.get(key);
        return v instanceof Boolean b && b;
    }

    /** 便捷方法:获取字符串类型的属性值 */
    public String getString(String key) {
        Object v = properties.get(key);
        return v instanceof String s ? s : null;
    }

    /** 检查当前注解是否匹配指定的标签 */
    public boolean is(SemanticTag t) {
        return tag == t;
    }
}
