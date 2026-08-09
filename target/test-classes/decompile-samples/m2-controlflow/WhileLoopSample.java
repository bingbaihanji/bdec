package test;

/** M2 control flow test: while loop. */
public class WhileLoopSample {
    public int test(int n) {
        int sum = 0;
        int i = 0;
        while (i < n) {
            sum += i;
            i++;
        }
        return sum;
    }
}
