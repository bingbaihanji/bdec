package com.bingbaihanji.bdec.decompiler.ir;

import com.bingbaihanji.bdec.decompiler.type.JavaType;

public interface Variable extends Value {

    int slot();

    int version();

    String name();

    @Override
    JavaType type();
}
