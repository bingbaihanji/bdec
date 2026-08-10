package com.bytecode.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestClass1 extends BaseClass implements InterfaceB {
    public List items;
    protected String message;
    private int counter;
    private String annotatedField;
    static final boolean $assertionsDisabled;
    public TestClass1(int id, String name) {
        super(id, name);
        this.items = new ArrayList();
        System.out.println("TestClass1 instance init");
        this.counter = 0;
        this.message = "Hello";
    }
    public TestClass1(int id, String name, String message) {
        this(id, name);
        this.message = message;
    }
    public static void main(String[] args) {
        com.bytecode.test.TestClass1 tc = new com.bytecode.test.TestClass1(5, "test");
        tc.testMethod(3, "hello");
        tc.defaultMethod();
        return;
    }
    public void abstractMethod() {
        System.out.println("Implement abstract method");
        return;
    }
    public void methodA() {
        System.out.println("methodA");
        return;
    }
    public void methodB() {
        System.out.println("methodB");
        return;
    }
    public void defaultMethod() {
        System.out.println("Overridden default method");
        return;
    }
    public String testMethod(int x, String s) {
        if (x >= 0) {
            if (x != 0) {
                if (x <= 10) {
                    String result = "other";
                }
                System.out.println("Case 1");
                System.out.println("Case 2");
                System.out.println("Default");
                int i = 0;
                i = 0;
            }
             else {
                return "zero";
            }
        }
         else {
            throw new IllegalArgumentException("x negative");
        }
    }
    static {
        if (TestClass1.class.desiredAssertionStatus()) {
            int varUnresolved = 0;
            $assertionsDisabled = varUnresolved;
            System.out.println("TestClass1 static init");
        }
    }
}
