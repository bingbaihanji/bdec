package com.bytecode.test;

import java.util.Iterator;
import java.util.List;

/**
 * 简单测试 for-each 循环的反编译。
 * 验证 for-each 模式正确识别，不产生原始 Iterator 变量。
 */
public class SimpleForEachTest {

    public static void main(String[] args) {
        List<String> items = List.of("a", "b", "c");
        for (String item : items) {
            IO.println(item);
        }
    }
}
