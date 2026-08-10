package com.bytecode.test;

class SealedChild2 extends SealedParent {
    SealedChild2() {
    }
    public void sealedMethod() {
        System.out.println("SealedChild2");
        return;
    }
}
