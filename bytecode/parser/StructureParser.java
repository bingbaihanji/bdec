package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ExceptionHandlerModel;
import com.bingbaihanji.bdec.bytecode.model.FieldModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpDouble;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpFloat;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpInteger;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpLong;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpString;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeResolver;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class StructureParser {

    private final InstructionDecoder insnDecoder = new InstructionDecoder();

    List<FieldModel> parseFields(DataInputStream in, ConstantPoolEntry[] pool, int count)
            throws IOException {
        List<FieldModel> fields = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int accessFlags = in.readUnsignedShort();
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String desc = ConstantPoolParser.utf8(pool, descIdx);
            JavaType type = TypeResolver.parseFieldDescriptor(desc);

            Object constValue = null;
            String signature = "";
            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);
                if ("Signature".equals(attrName)) {
                    int sigIdx = in.readUnsignedShort();
                    signature = ConstantPoolParser.utf8(pool, sigIdx);
                } else if ("ConstantValue".equals(attrName)) {
                    int cvIdx = in.readUnsignedShort();
                    ConstantPoolEntry entry = pool[cvIdx];
                    constValue = switch (entry) {
                        case CpInteger ci -> ci.value();
                        case CpFloat cf -> cf.value();
                        case CpLong cl -> cl.value();
                        case CpDouble cd -> cd.value();
                        case CpString cs -> ConstantPoolParser.utf8(pool, cs.stringIndex());
                        default -> "<unknown constant>";
                    };
                } else {
                    in.skipBytes(attrLen);
                }
            }
            fields.add(new FieldModel(accessFlags, name, type, constValue, signature));
        }
        return fields;
    }

    List<MethodModel> parseMethods(DataInputStream in, ConstantPoolEntry[] pool, int count)
            throws IOException {
        List<MethodModel> methods = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int accessFlags = in.readUnsignedShort();
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String desc = ConstantPoolParser.utf8(pool, descIdx);

            JavaType[] paramTypes = TypeResolver.parseMethodParameterTypes(desc);
            JavaType returnType = TypeResolver.parseMethodReturnType(desc);

            List<Instruction> instructions = null;
            List<ExceptionHandlerModel> handlers = List.of();
            int maxStack = 0, maxLocals = 0;
            String signature = "";

            int attrCount = in.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int attrNameIdx = in.readUnsignedShort();
                int attrLen = in.readInt();
                String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);

                if ("Signature".equals(attrName)) {
                    int sigIdx = in.readUnsignedShort();
                    signature = ConstantPoolParser.utf8(pool, sigIdx);
                } else if ("Code".equals(attrName)) {
                    maxStack = in.readUnsignedShort();
                    maxLocals = in.readUnsignedShort();
                    int codeLength = in.readInt();
                    byte[] code = new byte[codeLength];
                    in.readFully(code);
                    instructions = insnDecoder.decodeAll(code, 0, codeLength);

                    int excCount = in.readUnsignedShort();
                    handlers = new ArrayList<>();
                    for (int e = 0; e < excCount; e++) {
                        int startPc = in.readUnsignedShort();
                        int endPc = in.readUnsignedShort();
                        int handlerPc = in.readUnsignedShort();
                        int catchTypeIdx = in.readUnsignedShort();
                        String catchType = catchTypeIdx == 0 ? null
                                : ConstantPoolParser.className(pool, catchTypeIdx);
                        handlers.add(new ExceptionHandlerModel(startPc, endPc, handlerPc, catchType));
                    }

                    int codeAttrCount = in.readUnsignedShort();
                    for (int ca = 0; ca < codeAttrCount; ca++) {
                        in.readUnsignedShort();
                        int len = in.readInt();
                        in.skipBytes(len);
                    }
                } else {
                    in.skipBytes(attrLen);
                }
            }
            methods.add(new MethodModel(accessFlags, name, desc, returnType, paramTypes,
                    instructions, handlers, maxStack, maxLocals, signature));
        }
        return methods;
    }
}
