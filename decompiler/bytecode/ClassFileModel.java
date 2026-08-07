package com.bingbaihanji.bdec.decompiler.bytecode;

import java.util.List;

public interface ClassFileModel {

    int majorVersion();

    int minorVersion();

    int accessFlags();

    String internalName();

    String superInternalName();

    List<String> interfaceInternalNames();

    List<FieldModel> fields();

    List<MethodModel> methods();

    List<AttributeModel> attributes();
}
