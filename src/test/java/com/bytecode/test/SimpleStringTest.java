package com.bytecode.test;

/**
 * 简单测试字符串拼接的反编译.
 * 验证 makeConcatWithConstants 被重写为 + 表达式.
 */
public class SimpleStringTest {

    public static void main(String[] args) {
        String a = "Hello";
        String b = "World";
        // 简单拼接
        String c = a + " " + b;
        System.out.println(c);

        // 数字拼接
        int x = 42;
        String d = "value=" + x;
        System.out.println(d);
    }
}
