package com.bingbaihanji.bdec.bytecode.model.constantpool;

public sealed interface ConstantPoolEntry
        permits
        ConstantPoolEntry.CpUtf8,
        ConstantPoolEntry.CpInteger,
        ConstantPoolEntry.CpFloat,
        ConstantPoolEntry.CpLong,
        ConstantPoolEntry.CpDouble,
        ConstantPoolEntry.CpClass,
        ConstantPoolEntry.CpString,
        ConstantPoolEntry.CpFieldRef,
        ConstantPoolEntry.CpMethodRef,
        ConstantPoolEntry.CpInterfaceMethodRef,
        ConstantPoolEntry.CpNameAndType,
        ConstantPoolEntry.CpMethodHandle,
        ConstantPoolEntry.CpMethodType,
        ConstantPoolEntry.CpDynamic,
        ConstantPoolEntry.CpInvokeDynamic,
        ConstantPoolEntry.CpModule,
        ConstantPoolEntry.CpPackage {

    int tag();

    record CpUtf8(String value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 1;}
    }

    record CpInteger(int value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 3;}
    }

    record CpFloat(float value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 4;}
    }

    record CpLong(long value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 5;}
    }

    record CpDouble(double value) implements ConstantPoolEntry {

        @Override
        public int tag() {return 6;}
    }

    record CpClass(int nameIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 7;}
    }

    record CpString(int stringIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 8;}
    }

    record CpFieldRef(int classIndex,

                      int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 9;}
    }

    record CpMethodRef(int classIndex,

                       int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 10;}
    }

    record CpInterfaceMethodRef(int classIndex,

                                int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 11;}
    }

    record CpNameAndType(int nameIndex,

                         int descriptorIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 12;}
    }

    record CpMethodHandle(int referenceKind,

                          int referenceIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 15;}
    }

    record CpMethodType(int descriptorIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 16;}
    }

    record CpDynamic(int bootstrapMethodAttrIndex,

                     int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 17;}
    }

    record CpInvokeDynamic(int bootstrapMethodAttrIndex,

                           int nameAndTypeIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 18;}
    }

    record CpModule(int nameIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 19;}
    }

    record CpPackage(int nameIndex) implements ConstantPoolEntry {

        @Override
        public int tag() {return 20;}
    }
}
