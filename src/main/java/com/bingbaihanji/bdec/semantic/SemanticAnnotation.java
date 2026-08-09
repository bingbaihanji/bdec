package com.bingbaihanji.bdec.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A semantic annotation attached to an {@link com.bingbaihanji.bdec.ir.IrInstruction}.
 *
 * Each annotation carries a {@link SemanticTag} and optional typed properties.
 * Properties are stored as String→Object for flexibility; keys should use
 * the constants defined in this class.
 */
public record SemanticAnnotation(
        SemanticTag tag,
        Map<String, Object> properties
) {

    // ── Well-known property keys ──────────────────────────────────

    /** Boolean value for BOOLEAN_RETURN (Boolean). */
    public static final String KEY_BOOLEAN_VALUE = "booleanValue";

    /** Target class name for constructor delegation (String). */
    public static final String KEY_TARGET_CLASS = "targetClass";

    /** Monitor object variable name for SYNCHRONIZED_BLOCK (String). */
    public static final String KEY_MONITOR_OBJECT = "monitorObject";

    /** Original method name before elimination (String). */
    public static final String KEY_ORIGINAL_METHOD = "originalMethod";

    /** Declaring class name for static method calls (String). */
    public static final String KEY_DECLARING_CLASS = "declaringClass";

    // ── Factories ─────────────────────────────────────────────────

    /** Create an annotation with just a tag (no properties). */
    public static SemanticAnnotation of(SemanticTag tag) {
        return new SemanticAnnotation(tag, Collections.emptyMap());
    }

    /** Create an annotation with one property. */
    public static SemanticAnnotation of(SemanticTag tag, String key, Object value) {
        return new SemanticAnnotation(tag, Map.of(key, value));
    }

    /** Create an annotation with two properties. */
    public static SemanticAnnotation of(SemanticTag tag,
                                        String k1, Object v1,
                                        String k2, Object v2) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(k1, v1);
        props.put(k2, v2);
        return new SemanticAnnotation(tag, Collections.unmodifiableMap(props));
    }

    /** Create an annotation with a tag and a map of properties. */
    public static SemanticAnnotation of(SemanticTag tag, Map<String, Object> properties) {
        return new SemanticAnnotation(tag, Collections.unmodifiableMap(
                new LinkedHashMap<>(properties)));
    }

    /** Convenience: get a property value with a default. */
    public Object get(String key, Object defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /** Convenience: get a boolean property. */
    public boolean getBoolean(String key) {
        Object v = properties.get(key);
        return v instanceof Boolean b && b;
    }

    /** Convenience: get a string property. */
    public String getString(String key) {
        Object v = properties.get(key);
        return v instanceof String s ? s : null;
    }

    /** Check if this annotation has the given tag. */
    public boolean is(SemanticTag t) {
        return tag == t;
    }
}
