package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ClassFileReader {

    private static final int MAGIC = 0xCAFEBABE;

    private final ConstantPoolParser cpParser = new ConstantPoolParser();

    private final StructureParser structParser = new StructureParser();

    public ClassFileModel read(String internalName, byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Not a class file: bad magic 0x"
                    + Integer.toHexString(magic));
        }

        int minor = in.readUnsignedShort();
        int major = in.readUnsignedShort();
        ConstantPoolEntry[] pool = cpParser.parse(in);
        int accessFlags = in.readUnsignedShort();

        int thisClassIdx = in.readUnsignedShort();
        String thisClassName = ConstantPoolParser.className(pool, thisClassIdx);

        int superClassIdx = in.readUnsignedShort();
        String superName = superClassIdx == 0 ? null
                : ConstantPoolParser.className(pool, superClassIdx);

        int ifaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < ifaceCount; i++) {
            int idx = in.readUnsignedShort();
            interfaces.add(ConstantPoolParser.className(pool, idx));
        }

        int fieldCount = in.readUnsignedShort();
        var fields = structParser.parseFields(in, pool, fieldCount);

        int methodCount = in.readUnsignedShort();
        var methods = structParser.parseMethods(in, pool, methodCount);

        int attrCount = in.readUnsignedShort();
        for (int i = 0; i < attrCount; i++) {
            skipAttribute(in);
        }

        return new ClassFileModel(major, minor, accessFlags,
                thisClassName, superName, interfaces, fields, methods, pool);
    }

    private void skipAttribute(DataInputStream in) throws IOException {
        in.readUnsignedShort();
        int length = in.readInt();
        in.skipBytes(length);
    }
}
