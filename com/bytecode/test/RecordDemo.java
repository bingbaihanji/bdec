package com.bytecode.test;

public record RecordDemo(String name, int age) {
    public String greeting() {
        int name = 0;
        return "" + name;
    }
}
