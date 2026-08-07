package com.bingbaihanji.bdec.decompiler.emit;

import com.bingbaihanji.bdec.decompiler.type.JavaType;

import java.util.List;

public interface ImportManager {

    String qualify(JavaType type);

    List<String> imports();
}
