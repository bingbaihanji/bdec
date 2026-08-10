package com.bytecode.test;

// 接口,含 default/static/private 方法
public interface InterfaceA {

    int CONSTANT = 100;

    static void staticMethod() {
        System.out.println("Static method in InterfaceA");
    }

    void methodA();

    default void defaultMethod() {
        System.out.println("Default method in InterfaceA");
    }

    private void privateMethod() {
        System.out.println("Private method in InterfaceA");
    }
}