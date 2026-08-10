package com.bytecode.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

// 主测试类 2 —— 内部类、泛型、模式匹配、文本块、未命名变量等
public class TestClass2 {

    private int counter = 0;

    // 泛型方法
    public static <T> T genericMethod(T t) {
        return t;
    }

    // 通配符
    public static void wildcardMethod(List<? extends Number> list) {
        for (Number n : list) {
            System.out.println(n);
        }
    }

    public static void main(String[] args) {
        TestClass2 tc = new TestClass2();
        tc.localClassDemo();
        tc.anonymousDemo();
        tc.enumDemo();
        tc.recordDemo();
        tc.sealedDemo();
        System.out.println(tc.patternSwitch(5));
        System.out.println(tc.textBlockDemo());
        tc.unnamedDemo();
        tc.varDemo();
        tc.streamDemo();
        tc.annotatedMethod("test");
    }

    // 局部类
    public void localClassDemo() {
        class LocalClass {

            public void run() {
                System.out.println("LocalClass");
            }
        }
        LocalClass lc = new LocalClass();
        lc.run();
    }

    // 匿名类
    public void anonymousDemo() {
        InterfaceA a = new InterfaceA() {

            @Override
            public void methodA() {
                System.out.println("Anonymous InterfaceA");
            }
        };
        a.methodA();

        BaseClass bc = new BaseClass(0, "anon") {

            @Override
            public void abstractMethod() {
                System.out.println("Anonymous BaseClass");
            }
        };
        bc.abstractMethod();
    }

    // 枚举使用
    public void enumDemo() {
        EnumDemo e = EnumDemo.ONE;
        System.out.println(e.getValue());
        e.action();
    }

    // 记录使用 + 记录模式匹配
    public void recordDemo() {
        RecordDemo r = new RecordDemo("Alice", 25);
        System.out.println(r.name() + " " + r.age());
        System.out.println(r.greeting());

        Object obj = r;
        if (obj instanceof RecordDemo(String name, int age)) {
            System.out.println("Record pattern: " + name + ", " + age);
        }
    }

    // 密封类使用
    public void sealedDemo() {
        SealedParent sp = new SealedChild1();
        sp.sealedMethod();
        if (sp instanceof SealedChild1) {
            System.out.println("Is SealedChild1");
        }
    }

    // 注解使用（含参数）
    @AnnotationDemo(value = "class", count = 10)
    public void annotatedMethod(@AnnotationDemo("param") String param) {
        System.out.println(param);
    }

    // 模式匹配 switch（含 when 守卫）
    public String patternSwitch(Object obj) {
        return switch (obj) {
            case Integer i when i > 0 -> "positive int";
            case Integer i -> "non-positive int";
            case String s -> "string: " + s;
            case null -> "null value";
            default -> "unknown";
        };
    }

    // 文本块
    public String textBlockDemo() {
        return """
               Line 1
               Line 2
               Line 3
               """;
    }

    // 未命名变量（_）在 catch 和 lambda 中
    public void unnamedDemo() {
        try {
            int x = 10 / 0;
        } catch (Exception _) {
            System.out.println("Exception caught, but variable unused");
        }

        BiFunction<Integer, Integer, Integer> add = (a, _) -> a + 5;
        System.out.println(add.apply(10, 20));
    }

    // var 使用
    public void varDemo() {
        var list = new ArrayList<String>();
        list.add("var");
        var map = new HashMap<String, List<Integer>>();
        var entry = Map.entry("key", 123);
    }

    // Stream API
    public void streamDemo() {
        List<String> words = Arrays.asList("hello", "world", "java");
        words.stream()
                .filter(s -> s.length() > 3)
                .map(String::toUpperCase)
                .forEach(System.out::println);
    }

    // 静态内部类
    public static class StaticNested {

        private int value;

        public StaticNested(int value) {this.value = value;}

        public void print() {System.out.println("StaticNested: " + value);}
    }

    // 泛型类
    public static class GenericClass<T, U> {

        private T first;

        private U second;

        public GenericClass(T first, U second) {
            this.first = first;
            this.second = second;
        }

        public T getFirst() {return first;}

        public U getSecond() {return second;}
    }

    // 非静态内部类
    public class InnerClass {

        private final String name;

        public InnerClass(String name) {this.name = name;}

        public void display() {
            System.out.println("InnerClass: " + name + ", outer counter: " + counter);
        }
    }
}