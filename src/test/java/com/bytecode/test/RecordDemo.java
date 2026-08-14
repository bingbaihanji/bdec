package com.bytecode.test;

// 记录,含紧凑构造器和自定义方法
public record RecordDemo(String name, int age) {

    public RecordDemo {
        if (age < 0) {
            throw new IllegalArgumentException("Age cannot be negative");
        }
    }

    public String greeting() {
        return "Hello " + name;
    }
}
