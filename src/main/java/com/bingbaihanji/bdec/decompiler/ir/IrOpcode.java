package com.bingbaihanji.bdec.decompiler.ir;

public enum IrOpcode {
    ASSIGN,
    PHI,
    CONST,
    LOAD,
    STORE,
    ARRAY_LOAD,
    ARRAY_STORE,
    FIELD_LOAD,
    FIELD_STORE,
    INVOKE,
    NEW,
    NEW_ARRAY,
    CAST,
    INSTANCE_OF,
    UNARY,
    BINARY,
    RETURN,
    THROW
}
