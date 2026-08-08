package com.bingbaihanji.bdec.bytecode.model.constantpool;

import java.util.List;

/** Represents one entry in the BootstrapMethods class attribute. */
public record BootstrapMethodEntry(
        int methodRef,                  // index to CONSTANT_MethodHandle_info
        List<Integer> arguments         // indices to constant pool entries
) {}
