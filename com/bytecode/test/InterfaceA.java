package com.bytecode.test;

public interface InterfaceA {
    public static final int CONSTANT = 100;
    public static void staticMethod() {
        System.out.println("Static method in InterfaceA");
        return;
    }
    public default void defaultMethod() {
        System.out.println("Default method in InterfaceA");
        return;
    }
    private void privateMethod() {
        System.out.println("Private method in InterfaceA");
        return;
    }
}
