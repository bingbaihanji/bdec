package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.RecordComponentEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClassFileReader {

    private static final int MAGIC = 0xCAFEBABE;

    private final ConstantPoolParser cpParser = new ConstantPoolParser();

    private final StructureParser structParser = new StructureParser();

    /** Parse the BootstrapMethods class attribute. */
    private List<BootstrapMethodEntry> parseBootstrapMethods(DataInputStream in,
                                                              ConstantPoolEntry[] pool)
            throws IOException {
        int count = in.readUnsignedShort();
        List<BootstrapMethodEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int methodRef = in.readUnsignedShort();
            int argCount = in.readUnsignedShort();
            List<Integer> arguments = new ArrayList<>(argCount);
            for (int j = 0; j < argCount; j++) {
                arguments.add(in.readUnsignedShort());
            }
            entries.add(new BootstrapMethodEntry(methodRef, arguments));
        }
        return entries;
    }

    /** Parse the Record class attribute (Java 16+). */
    private List<RecordComponentEntry> parseRecordComponents(DataInputStream in,
                                                              ConstantPoolEntry[] pool)
            throws IOException {
        int count = in.readUnsignedShort();
        List<RecordComponentEntry> components = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String descriptor = ConstantPoolParser.utf8(pool, descIdx);
            // Skip component attributes
            int compAttrCount = in.readUnsignedShort();
            for (int j = 0; j < compAttrCount; j++) {
                in.readUnsignedShort(); // attr name
                int attrLen = in.readInt();
                in.skipBytes(attrLen);
            }
            components.add(new RecordComponentEntry(name, descriptor));
        }
        return components;
    }

    /** Parse the PermittedSubclasses class attribute (Java 17+). */
    private List<String> parsePermittedSubclasses(DataInputStream in,
                                                   ConstantPoolEntry[] pool)
            throws IOException {
        int count = in.readUnsignedShort();
        List<String> classes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int classIdx = in.readUnsignedShort();
            classes.add(ConstantPoolParser.className(pool, classIdx));
        }
        return classes;
    }

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
        String signature = "";
        List<BootstrapMethodEntry> bootstrapMethods = Collections.emptyList();
        List<RecordComponentEntry> recordComponents = Collections.emptyList();
        List<String> permittedSubclasses = Collections.emptyList();
        for (int i = 0; i < attrCount; i++) {
            int attrNameIdx = in.readUnsignedShort();
            int attrLen = in.readInt();
            String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);
            switch (attrName) {
                case "Signature" -> {
                    int sigIdx = in.readUnsignedShort();
                    signature = ConstantPoolParser.utf8(pool, sigIdx);
                }
                case "BootstrapMethods" -> {
                    bootstrapMethods = parseBootstrapMethods(in, pool);
                }
                case "Record" -> {
                    recordComponents = parseRecordComponents(in, pool);
                }
                case "PermittedSubclasses" -> {
                    permittedSubclasses = parsePermittedSubclasses(in, pool);
                }
                default -> in.skipBytes(attrLen);
            }
        }

        return new ClassFileModel(major, minor, accessFlags,
                thisClassName, superName, interfaces, fields, methods, pool, signature,
                bootstrapMethods, recordComponents, permittedSubclasses);
    }
}
