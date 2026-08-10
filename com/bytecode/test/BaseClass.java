package com.bytecode.test;

public abstract class BaseClass {
    public static final double PI = 3.14159;
    protected String name;
    volatile boolean flag;
    transient int temp;
    private int id;
    public BaseClass(int id, String name) {
        System.out.println("Instance initializer");
        this.id = id;
        this.name = name;
    }
    public abstract void abstractMethod() ;
    public void concreteMethod() {
        System.out.println("Concrete method");
        return;
    }
    public synchronized void synchronizedMethod() {
        Object var1 = this;
        try {
            System.out.println("Synchronized block");
            while (true) {
                Throwable var2;
                {
                    Throwable var2;
                    throw var2;
                }
            }
            return;
        }
         finally {
        }
    }
    public native void nativeMethod() ;
    public double strictfpMethod() {
        return 0.0;
    }
    static {
        System.out.println("Static initializer");
        return;
    }
}
