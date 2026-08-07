package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;

import java.util.List;

public record ClassFileModel(
        int majorVersion,
        int minorVersion,
        int accessFlags,
        String internalName,
        String superInternalName,
        List<String> interfaceInternalNames,
        List<FieldModel> fields,
        List<MethodModel> methods,
        ConstantPoolEntry[] constantPool
) {}
