package com.bingbaihanji.bdec.decompiler.type;

import java.util.List;

public interface JavaType {

    TypeKind kind();

    String displayName();

    String descriptor();

    List<JavaType> typeArguments();

    int arrayDimensions();
}
