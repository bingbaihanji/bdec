package com.bytecode.test;

// 密封类及其子类
public sealed class SealedParent permits SealedChild1, SealedChild2 {

    public void sealedMethod() {
        System.out.println("Sealed method");
    }
}

final class SealedChild1 extends SealedParent {

    @Override
    public void sealedMethod() {
        System.out.println("SealedChild1");
    }
}

non-sealed class SealedChild2 extends SealedParent {

    @Override
    public void sealedMethod() {
        System.out.println("SealedChild2");
    }
}