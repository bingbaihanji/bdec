package com.bingbaihanji.bdec.decompiler.cfg;

public enum EdgeKind {
    ENTRY,
    FALL_THROUGH,
    TRUE_BRANCH,
    FALSE_BRANCH,
    GOTO,
    SWITCH_CASE,
    SWITCH_DEFAULT,
    EXCEPTION,
    RETURN,
    THROW
}
