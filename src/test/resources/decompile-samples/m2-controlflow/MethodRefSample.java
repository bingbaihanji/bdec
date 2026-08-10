package test;

import java.util.function.Function;

/** P6 test: method reference String::length. */
public class MethodRefSample {

    public Function<String, Integer> test() {
        return String::length;
    }
}
