package com.bytecode.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.System.out;

// 主测试类 1 —— 基础语法与控制流
public class TestClass1 extends BaseClass implements InterfaceB {

    static {
        out.println("TestClass1 static init");
    }

    public List<String> items = new ArrayList<>();

    protected String message;

    private int counter;

    @AnnotationDemo(value = "field", count = 1)
    private String annotatedField;

    {
        out.println("TestClass1 instance init");
        counter = 0;
    }

    public TestClass1(int id, String name) {
        super(id, name);
        this.message = "Hello";
    }

    public TestClass1(int id, String name, String message) {
        this(id, name);
        this.message = message;
    }

    public static void main(String[] args) throws Exception {
        TestClass1 tc = new TestClass1(5, "test");
        tc.testMethod(3, "hello");
        tc.defaultMethod();
    }

    @Override
    public void abstractMethod() {
        out.println("Implement abstract method");
    }

    @Override
    public void methodA() {
        out.println("methodA");
    }

    @Override
    public void methodB() {
        out.println("methodB");
    }

    @Override
    public void defaultMethod() {

        out.println("Overridden default method");
    }

    @AnnotationDemo(value = "testMethod", count = 5)
    public String testMethod(int x, String s) throws IOException, InterruptedException {
        if (x < 0) {
            throw new IllegalArgumentException("x negative");
        } else if (x == 0) {
            return "zero";
        } else {
            // switch expression with yield
            String result = switch (x) {
                case 1 -> "one";
                case 2 -> "two";
                case 3, 4 -> "three or four";
                default -> {
                    if (x > 10) {
                        yield "big";
                    } else {
                        yield "other";
                    }
                }
            };

            // traditional switch
            switch (x) {
                case 1:
                    out.println("Case 1");
                    break;
                case 2:
                    out.println("Case 2");
                    break;
                default:
                    out.println("Default");
            }

            // for, continue, break
            for (int i = 0; i < 10; i++) {
                if (i == 5) {
                    continue;
                }
                out.println(i);
                if (i == 8) {
                    break;
                }
            }

            // while
            int j = 0;
            while (j < 5) {
                out.println(j++);
            }

            // do-while
            do {
                out.println(j--);
            } while (j > 0);

            // for-each
            List<String> list = Arrays.asList("a", "b", "c");
            for (String item : list) {
                out.println(item);
            }

            // try-catch-finally
            try {
                int res = 10 / x;
            } catch (ArithmeticException e) {
                out.println("Arithmetic: " + e);
            } finally {
                out.println("Finally");
            }

            // try-with-resources
            try (BufferedReader br = new BufferedReader(new StringReader(s));
                 PrintWriter pw = new PrintWriter(new StringWriter())
            ) {
                String line = br.readLine();
                pw.println(line);
            } catch (IOException e) {
                throw new IOException("IO Error", e);
            }

            // assert
            assert x > 0 : "x should be positive";

            // synchronized block
            synchronized (this) {
                counter++;
            }

            // var
            var list2 = new ArrayList<String>();
            list2.add("var test");

            // lambda
            Runnable run = () -> out.println("Lambda");
            run.run();

            // method reference
            List<String> names = Arrays.asList("Alice", "Bob");
            names.forEach(out::println);

            return "done";
        }
    }
}
