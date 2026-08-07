package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;

public record FieldModel(
        int accessFlags,
        String name,
        JavaType type,
        Object constantValue
) {}
