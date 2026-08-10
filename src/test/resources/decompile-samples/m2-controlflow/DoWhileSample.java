package test;

/** M2 control flow test: do-while loop. */
public class DoWhileSample {

    public int test(int n) {
        int i = 0;
        do {
            i++;
        } while (i < n);
        return i;
    }
}
