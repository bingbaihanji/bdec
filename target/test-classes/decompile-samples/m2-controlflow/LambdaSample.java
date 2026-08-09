package test;

import java.util.function.Function;

/** P6 test: lambda expression. */
public class LambdaSample {
    public Function<Integer, Integer> test() {
        return x -> x * 2;
    }
}
