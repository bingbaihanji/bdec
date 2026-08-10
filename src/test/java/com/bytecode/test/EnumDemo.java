package com.bytecode.test;

// 枚举,带抽象方法和常量特定实现
public enum EnumDemo {
    ONE(1) {
        @Override
        public void action() {
            System.out.println("One");
        }
    },
    TWO(2) {
        @Override
        public void action() {
            System.out.println("Two");
        }
    };

    private final int value;

    EnumDemo(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public abstract void action();
}