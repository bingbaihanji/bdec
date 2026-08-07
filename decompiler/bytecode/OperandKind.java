package com.bingbaihanji.bdec.decompiler.bytecode;

public enum OperandKind {
    CONSTANT_POOL_INDEX,
    LOCAL_SLOT,
    BRANCH_TARGET,
    SWITCH_TABLE,
    IMMEDIATE,
    TYPE_DESCRIPTOR,
    FIELD_REFERENCE,
    METHOD_REFERENCE,
    DYNAMIC_REFERENCE
}
