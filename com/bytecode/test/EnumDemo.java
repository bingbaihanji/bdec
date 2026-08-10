package com.bytecode.test;

public enum EnumDemo {
    ONE, TWO;
    private final int value;
    private static final com.bytecode.test.EnumDemo[] $VALUES;
    private EnumDemo(int value) {
        int var2 = 0;
        int var1 = 0;
        super(var1, var2);
        this.value = value;
    }
    public int getValue() {
        return value;
    }
    public abstract void action() ;
    static {
        int varUnresolved = 0;
        ONE = varUnresolved;
        TWO = varUnresolved;
        $VALUES = varUnresolved;
        return;
    }
}
