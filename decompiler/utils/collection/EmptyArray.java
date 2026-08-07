package com.bingbaihanji.bdec.decompiler.utils.collection;

/**
 * 空数组常量
 *
 * <p>
 * 用于避免频繁创建长度为 0 的数组实例,整个集合框架共享这些对象
 *
 * <p>
 * 参考 Android ArrayMap / ArraySet 的设计思想
 *
 * @author bingbaihanji
 * @since 1.0
 */
public final class EmptyArray {

    /**
     * 空 Object 数组
     */
    public static final Object[] OBJECT = new Object[0];

    /**
     * 空 int 数组
     */
    public static final int[] INT = new int[0];

    /**
     * 空 long 数组
     */
    public static final long[] LONG = new long[0];

    /**
     * 空 boolean 数组
     */
    public static final boolean[] BOOLEAN = new boolean[0];

    /**
     * 空 byte 数组
     */
    public static final byte[] BYTE = new byte[0];

    /**
     * 空 char 数组
     */
    public static final char[] CHAR = new char[0];

    /**
     * 空 short 数组
     */
    public static final short[] SHORT = new short[0];

    /**
     * 空 float 数组
     */
    public static final float[] FLOAT = new float[0];

    /**
     * 空 double 数组
     */
    public static final double[] DOUBLE = new double[0];

    /**
     * 空 String 数组
     */
    public static final String[] STRING = new String[0];

    /**
     * 禁止实例化
     */
    private EmptyArray() {
        throw new AssertionError("No instances.");
    }

}
