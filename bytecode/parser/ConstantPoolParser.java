package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.*;

import java.io.DataInputStream;
import java.io.IOException;

public final class ConstantPoolParser {

    public static String utf8(ConstantPoolEntry[] pool, int index) {
        return ((CpUtf8) pool[index]).value();
    }

    public static String className(ConstantPoolEntry[] pool, int classIndex) {
        CpClass c = (CpClass) pool[classIndex];
        return utf8(pool, c.nameIndex());
    }

    public ConstantPoolEntry[] parse(DataInputStream in) throws IOException {
        int cpCount = in.readUnsignedShort();
        ConstantPoolEntry[] pool = new ConstantPoolEntry[cpCount];

        int i = 1;
        while (i < cpCount) {
            int tag = in.readUnsignedByte();
            ConstantPoolEntry entry = switch (tag) {
                case 1 -> new CpUtf8(in.readUTF());
                case 3 -> new CpInteger(in.readInt());
                case 4 -> new CpFloat(in.readFloat());
                case 5 -> new CpLong(in.readLong());
                case 6 -> new CpDouble(in.readDouble());
                case 7 -> new CpClass(in.readUnsignedShort());
                case 8 -> new CpString(in.readUnsignedShort());
                case 9 -> new CpFieldRef(in.readUnsignedShort(), in.readUnsignedShort());
                case 10 -> new CpMethodRef(in.readUnsignedShort(), in.readUnsignedShort());
                case 11 -> new CpInterfaceMethodRef(in.readUnsignedShort(), in.readUnsignedShort());
                case 12 -> new CpNameAndType(in.readUnsignedShort(), in.readUnsignedShort());
                case 15 -> new CpMethodHandle(in.readUnsignedByte(), in.readUnsignedShort());
                case 16 -> new CpMethodType(in.readUnsignedShort());
                case 17 -> new CpDynamic(in.readUnsignedShort(), in.readUnsignedShort());
                case 18 -> new CpInvokeDynamic(in.readUnsignedShort(), in.readUnsignedShort());
                case 19 -> new CpModule(in.readUnsignedShort());
                case 20 -> new CpPackage(in.readUnsignedShort());
                default -> throw new IOException("Unknown constant pool tag: " + tag + " at index " + i);
            };
            pool[i] = entry;
            if (tag == 5 || tag == 6) {
                i += 2;
            } else {
                i++;
            }
        }
        return pool;
    }
}
