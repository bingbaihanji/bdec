package com.bytecode.test;

public class CycleTest {
    static void main() {
        int k = 0;
        loop1: for (int i = 0; i < 3; i++) {
            IO.println("i = " + i);
            for (int m = 0; m < 9; m++) {
                IO.println(m);
                k++;
                if (m == 8) {
                    continue loop1;
                }
                // 另外保留原k==10的判断，但不会触发
                if (k == 10) {
                    break;
                }
            }
        }
    }
}
