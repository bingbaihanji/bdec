package com.bytecode.test;

// 抽象类，包含多种修饰符
public abstract class BaseClass {

    public static final double PI = 3.14159;

    static {
        System.out.println("Static initializer");
    }

    protected String name;

    volatile boolean flag;

    transient int temp;

    private int id;

    {
        System.out.println("Instance initializer");
    }

    public BaseClass(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public abstract void abstractMethod();

    public void concreteMethod() {
        System.out.println("Concrete method");
    }

    public synchronized void synchronizedMethod() {
        synchronized (this) {
            System.out.println("Synchronized block");
        }
    }

    public native void nativeMethod();

    public double strictfpMethod() {
        return 0.0;
    }
}