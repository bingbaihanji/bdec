package com.bingbaihanji.bdec.decompiler.type;

import com.bingbaihanji.bdec.decompiler.DecompileContext;

public interface TypeResolver {

    JavaType fromDescriptor(String descriptor, DecompileContext context);

    JavaType fromInternalName(String internalName, DecompileContext context);

    JavaType commonSuperType(JavaType left, JavaType right, DecompileContext context);
}
