package com.bytecode.test;

public abstract enum EnumDemo extends Enum {
    public static final com.bytecode.test.EnumDemo ONE;
    public static final com.bytecode.test.EnumDemo TWO;
    private final int value;
    private EnumDemo(String param0, int param1, int value) {
        int var2 = 0;
        int var1 = 0;
        super(var1, var2);
        this.value = value;
    }
    public int getValue() {
        return value;
    }
    public abstract void action() ;
    private static com.bytecode.test.EnumDemo[] $values() {
        (new com.bytecode.test.EnumDemo[2])[0] = EnumDemo.ONE;
        (new com.bytecode.test.EnumDemo[2])[1] = EnumDemo.TWO;
        return new com.bytecode.test.EnumDemo[2];
    }
    static {
        int ? = 0;
        ONE = ?;
        TWO = ?;
        $VALUES = ?;
        return;
    }
}
