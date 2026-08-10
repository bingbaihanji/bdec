package com.bytecode.test;

import java.util.ArrayList;

/**
 * 简单测试 NEW + INIT 合并。
 * 验证 new Xxx(args) 不会被拆分成多条语句。
 */
public class SimpleNewTest {

    public static void main(String[] args) {
        // 基本构造函数调用
        ArrayList<String> list = new ArrayList<>();
        list.add("hello");

        // 带参数的构造函数调用
        StringBuilder sb = new StringBuilder("test");
        sb.append("ing");

        System.out.println(list);
        System.out.println(sb);
    }
}
