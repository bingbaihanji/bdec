package com.bytecode.test;

final class SealedChild1 extends SealedParent {
    SealedChild1() {
    }
    public void sealedMethod() {
        System.out.println("SealedChild1");
        return;
    }
}
