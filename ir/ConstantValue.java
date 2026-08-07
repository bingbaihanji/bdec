package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

public record ConstantValue(Object value, JavaType type) implements Value {

    public static final ConstantValue NULL = new ConstantValue(null, JavaType.INT); // placeholder type

    public boolean isNull() {return value == null;}

    public boolean isZero() {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case Integer i -> i == 0;
            case Long l -> l == 0L;
            case Float f -> f == 0.0f;
            case Double d -> d == 0.0;
            case Boolean b -> !b;
            default -> false;
        };
    }
}
