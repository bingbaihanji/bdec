package com.bingbaihanji.bdec.decompiler.bytecode;

import com.bingbaihanji.bdec.decompiler.DecompileContext;

public interface BytecodeParser {

    ClassFileModel parse(String internalName, byte[] classBytes, DecompileContext context);
}
