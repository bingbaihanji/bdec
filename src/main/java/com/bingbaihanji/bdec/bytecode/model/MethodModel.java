package com.bingbaihanji.bdec.bytecode.model;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

public record MethodModel(
        int accessFlags,
        String name,
        String descriptor,
        JavaType returnType,
        JavaType[] parameterTypes,
        List<Instruction> instructions,
        List<ExceptionHandlerModel> exceptionHandlers,
        int maxStack,
        int maxLocals,
        String signature
) {

    /** Backward-compatible constructor without signature. */
    public MethodModel(int accessFlags, String name, String descriptor,
                       JavaType returnType, JavaType[] parameterTypes,
                       List<Instruction> instructions,
                       List<ExceptionHandlerModel> exceptionHandlers,
                       int maxStack, int maxLocals) {
        this(accessFlags, name, descriptor, returnType, parameterTypes,
                instructions, exceptionHandlers, maxStack, maxLocals, "");
    }

    public boolean isAbstract() {return (accessFlags & 0x0400) != 0;}

    public boolean isNative() {return (accessFlags & 0x0100) != 0;}

    public boolean isStatic() {return (accessFlags & 0x0008) != 0;}
}
