package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.RecordComponentEntry;

import java.util.Collections;
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
        ConstantPoolEntry[] constantPool,
        String signature,
        List<BootstrapMethodEntry> bootstrapMethods,
        List<RecordComponentEntry> recordComponents,
        List<String> permittedSubclasses,
        List<InnerClassEntry> innerClasses
) {

    /** Backward-compatible constructor without signature and bootstrap methods. */
    public ClassFileModel(int majorVersion, int minorVersion, int accessFlags,
                          String internalName, String superInternalName,
                          List<String> interfaceInternalNames, List<FieldModel> fields,
                          List<MethodModel> methods, ConstantPoolEntry[] constantPool) {
        this(majorVersion, minorVersion, accessFlags, internalName, superInternalName,
                interfaceInternalNames, fields, methods, constantPool, "",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
    }

    /** Constructor with signature but no bootstrap methods. */
    public ClassFileModel(int majorVersion, int minorVersion, int accessFlags,
                          String internalName, String superInternalName,
                          List<String> interfaceInternalNames, List<FieldModel> fields,
                          List<MethodModel> methods, ConstantPoolEntry[] constantPool,
                          String signature) {
        this(majorVersion, minorVersion, accessFlags, internalName, superInternalName,
                interfaceInternalNames, fields, methods, constantPool, signature,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
    }
}
