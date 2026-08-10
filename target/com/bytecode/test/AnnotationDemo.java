package com.bytecode.test;

import java.lang.annotation.Annotation;

public interface AnnotationDemo extends Annotation {
    public abstract String value() ;
    public abstract int count() ;
}
