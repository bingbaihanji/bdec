package com.bingbaihanji.bdec.decompiler.bytecode;

import java.util.List;

public interface FieldModel {

    int accessFlags();

    String name();

    String descriptor();

    String signature();

    Object constantValue();

    List<AttributeModel> attributes();
}
