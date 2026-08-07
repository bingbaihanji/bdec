package com.bingbaihanji.bdec.decompiler.bytecode;

import java.util.List;

public interface MethodModel {

    int accessFlags();

    String name();

    String descriptor();

    String signature();

    List<Instruction> instructions();

    List<ExceptionHandlerModel> exceptionHandlers();

    List<LocalVariableModel> localVariables();

    List<LineNumberModel> lineNumbers();

    List<AttributeModel> attributes();
}
