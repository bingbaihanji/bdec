package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;

public record FieldModel(
        int accessFlags,
        String name,
        JavaType type,
        Object constantValue,
        String signature
) {

    /** Backward-compatible constructor without signature. */
    public FieldModel(int accessFlags, String name, JavaType type, Object constantValue) {
        this(accessFlags, name, type, constantValue, "");
    }
}
