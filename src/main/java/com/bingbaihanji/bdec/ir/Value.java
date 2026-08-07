package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

public sealed interface Value permits Variable, ConstantValue, InstructionRef {

    JavaType type();
}
