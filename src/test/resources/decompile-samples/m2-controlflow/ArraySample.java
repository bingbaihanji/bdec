package test;

/** P3 test: array access and element operations. */
public class ArraySample {

    public int test(int[] arr, int idx) {
        int a = arr[idx];         // array load
        arr[idx] = a + 1;         // array store
        return arr[0];            // array load again
    }
}
