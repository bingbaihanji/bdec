package com.bytecode.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestClass2 {
    private int counter;
    public TestClass2() {
        this.counter = 0;
    }
    public static Object genericMethod(Object t) {
        return t;
    }
    public static void wildcardMethod(List list) {
        while (true) {
            Iterator var1 = list.iterator();
        }
        return;
    }
    public static void main(String[] args) {
        com.bytecode.test.TestClass2 tc = new com.bytecode.test.TestClass2();
        tc.localClassDemo();
        tc.anonymousDemo();
        tc.enumDemo();
        tc.recordDemo();
        tc.sealedDemo();
        System.out.println(tc.patternSwitch(Integer.valueOf(5)));
        System.out.println(tc.textBlockDemo());
        tc.unnamedDemo();
        tc.varDemo();
        tc.streamDemo();
        tc.annotatedMethod("test");
        return;
    }
    public void localClassDemo() {
        com.bytecode.test.TestClass2$1LocalClass lc = new com.bytecode.test.TestClass2$1LocalClass(this);
        lc.run();
        return;
    }
    public void anonymousDemo() {
        com.bytecode.test.TestClass2$1 a = new com.bytecode.test.TestClass2$1(this);
        a.methodA();
        com.bytecode.test.TestClass2$2 bc = new com.bytecode.test.TestClass2$2(this, 0, "anon");
        bc.abstractMethod();
        return;
    }
    public void enumDemo() {
        com.bytecode.test.EnumDemo e = EnumDemo.ONE;
        System.out.println(e.getValue());
        e.action();
        return;
    }
    public void recordDemo() {
        RecordDemo("Alice", 25);
        com.bytecode.test.RecordDemo r = new com.bytecode.test.RecordDemo();
        try {
            System.out;
            int r = 0;
            makeConcatWithConstants(r.name(), r.age());
            if (true) {
                return;
            }
             else {
                com.bytecode.test.RecordDemo var3 = (com.bytecode.test.RecordDemo) obj;
                return var3.name();
                String var6 = var3.name();
                String name = var6;
                return var3.age();
                var6 = var3.age();
                int var7 = var6;
                if (1 == 0) {
                    int var6 = 0;
                    int age = var6;
                    System.out;
                    int name = 0;
                    makeConcatWithConstants(name, age);
                }
            }
        }
         finally {
            String var3 = var3.name();
            MatchException(var3.toString(), var3);
        }
    }
    public void sealedDemo() {
        SealedChild1();
        com.bytecode.test.SealedChild1 sp = new com.bytecode.test.SealedChild1();
        sp.sealedMethod();
        if (sp instanceof com.bytecode.test.SealedChild1 == 0) {
            System.out.println("Is SealedChild1");
            return;
        }
    }
    public void annotatedMethod(String param) {
        System.out.println(param);
        return;
    }
    public String patternSwitch(Object obj) {
        while (true) {
            Object var2 = obj;
            int var3 = 0;
        }
        {
        }
        Integer i = (Integer) var2;
        "" + (String) var2;
        {
        }
        {
        }
        return "positive int";
    }
    public String textBlockDemo() {
        return "Line 1\nLine 2\nLine 3\n";
    }
    public void unnamedDemo() {
        try {
            int add = 10 / 0;
        }
         catch (Exception e) {
            System.out.println("Exception caught, but variable unused");
        }
        {
            System.out.println("Exception caught, but variable unused");
            () -> /* lambda$unnamedDemo$0 */;
        }
    }
    public void varDemo() {
        ArrayList list = new ArrayList();
        list.add("var");
        HashMap map = new HashMap();
        Map$Entry entry = Map.entry("key", Integer.valueOf(123));
        return;
    }
    public void streamDemo() {
        (new String[3])[0] = "hello";
        (new String[3])[1] = "world";
        (new String[3])[2] = "java";
        List words = Arrays.asList(new String[3]);
        words.stream();
        () -> /* lambda$streamDemo$0 */;
    }
}
