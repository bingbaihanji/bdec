package com.bingbaihanji.bdec;

/**
 * 测试夹具:手工构造含 {@code CONSTANT_Dynamic}(condy)常量的最小类字节码.
 *
 * <p>javac 不直接产生 {@code CONSTANT_Dynamic},本构建器用
 * {@code java.lang.invoke.ConstantBootstraps} 的各标准引导方法
 * (getStaticFinal / enumConstant / nullConstant / primitiveClass)
 * 组装出可反编译的 {@code CondyHolder.class} 字节.</p>
 *
 * <p>从 {@link BytecodeTestRoundTripTest} 抽出,使 671 行测试类
 * 不再混入字节码手工构造细节.</p>
 */
final class CondyBytecodeBuilder {

    private CondyBytecodeBuilder() {}

    /**
     * 构造:
     *
     * <pre>
     * public class CondyHolder {
     *     static Object getStaticFinal() { return CondyHolder.VALUE; }   // getStaticFinal
     *     static Object getEnum() { return TimeUnit.SECONDS; }           // enumConstant
     *     static Object getNull() { return null; }                       // nullConstant
     *     static Object getPrimitive() { return int.class; }             // primitiveClass
     * }
     * </pre>
     */
    static byte[] buildCondyClass() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        // 常量池构建器(条目:字符串 或 int[]{...,种类标记})
        CpBuilder cp = new CpBuilder();

        // 引导方法定义(ConstantBootstraps)
        int uCB = cp.u("java/lang/invoke/ConstantBootstraps");
        int uBsmDesc = cp.u("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;");
        int uGSF = cp.u("getStaticFinal");
        int uEC = cp.u("enumConstant");
        int uNC = cp.u("nullConstant");
        int uPC = cp.u("primitiveClass");
        int clCB = cp.c(uCB);
        int mhGSF = cp.h(6, clCB, cp.n(uGSF, uBsmDesc)); // REF_invokeStatic
        int mhEC = cp.h(6, clCB, cp.n(uEC, uBsmDesc));
        int mhNC = cp.h(6, clCB, cp.n(uNC, uBsmDesc));
        int mhPC = cp.h(6, clCB, cp.n(uPC, uBsmDesc));
        // 动态常量条目
        int uVALUE = cp.u("VALUE");
        int clHolder = cp.c(cp.u("CondyHolder"));
        int sVALUE = cp.s(uVALUE);
        int dynGSF = cp.d(0, cp.n(uVALUE, cp.u("Ljava/lang/Integer;")));
        int clTimeUnit = cp.c(cp.u("java/util/concurrent/TimeUnit"));
        int sSECONDS = cp.s(cp.u("SECONDS"));
        int dynEC = cp.d(1, cp.n(cp.u("SECONDS"), cp.u("Ljava/util/concurrent/TimeUnit;")));
        int dynNC = cp.d(2, cp.n(cp.u("nullConst"), cp.u("Ljava/lang/Object;")));
        int clI = cp.c(cp.u("I"));
        int dynPC = cp.d(3, cp.n(cp.u("int"), cp.u("Ljava/lang/Class;")));
        // Object 与属性/方法名
        int clObj = cp.c(cp.u("java/lang/Object"));
        int uCode = cp.u("Code");
        int uBootstrapMethods = cp.u("BootstrapMethods");
        int uObjDesc = cp.u("()Ljava/lang/Object;");
        int uGSFm = cp.u("getStaticFinal");
        int uECm = cp.u("getEnum");
        int uNCm = cp.u("getNull");
        int uPCm = cp.u("getPrimitive");

        out.writeInt(0xCAFEBABE);
        out.writeShort(0);  // minor
        out.writeShort(61); // major (Java 17)
        // 常量池
        out.writeShort(cp.entries().size());
        for (int i = 1; i < cp.entries().size(); i++) {
            Object e = cp.entries().get(i);
            if (e instanceof String s) {
                out.writeByte(1);
                out.writeUTF(s);
            } else {
                int[] a = (int[]) e;
                switch (a[a.length - 1]) {
                    case 0 -> {out.writeByte(7); out.writeShort(a[0]);}                    // Class
                    case 1 -> {out.writeByte(12); out.writeShort(a[0]); out.writeShort(a[1]);} // NameAndType
                    case 2 -> {out.writeByte(8); out.writeShort(a[0]);}                    // String
                    case 3 -> {out.writeByte(15); out.writeByte(a[0]); out.writeShort(a[1]);} // MethodHandle
                    case 4 -> {out.writeByte(17); out.writeShort(a[0]); out.writeShort(a[1]);} // Dynamic
                    case 5 -> {out.writeByte(10); out.writeShort(a[0]); out.writeShort(a[1]);} // Methodref
                    default -> throw new IllegalStateException("bad cp entry");
                }
            }
        }
        out.writeShort(0x0021); // public super
        out.writeShort(clHolder); // this
        out.writeShort(clObj);    // super
        out.writeShort(0);        // interfaces
        out.writeShort(0);        // fields
        out.writeShort(4);        // methods
        writeCondyMethod(out, uGSFm, uObjDesc, dynGSF, uCode);
        writeCondyMethod(out, uECm, uObjDesc, dynEC, uCode);
        writeCondyMethod(out, uNCm, uObjDesc, dynNC, uCode);
        writeCondyMethod(out, uPCm, uObjDesc, dynPC, uCode);
        // 属性:BootstrapMethods
        out.writeShort(1);
        out.writeShort(uBootstrapMethods);
        java.io.ByteArrayOutputStream bsmBos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream bsm = new java.io.DataOutputStream(bsmBos);
        bsm.writeShort(4); // 4 个引导方法
        // getStaticFinal(CondyHolder.class, "VALUE")
        bsm.writeShort(mhGSF); bsm.writeShort(2); bsm.writeShort(clHolder); bsm.writeShort(sVALUE);
        // enumConstant(TimeUnit.class, "SECONDS")
        bsm.writeShort(mhEC); bsm.writeShort(2); bsm.writeShort(clTimeUnit); bsm.writeShort(sSECONDS);
        // nullConstant()
        bsm.writeShort(mhNC); bsm.writeShort(0);
        // primitiveClass(int.class)
        bsm.writeShort(mhPC); bsm.writeShort(1); bsm.writeShort(clI);
        byte[] bsmBytes = bsmBos.toByteArray();
        out.writeInt(bsmBytes.length);
        out.write(bsmBytes);
        out.flush();
        return bos.toByteArray();
    }

    /** 常量池构建器:维护条目列表,提供各类型的快捷添加方法. */
    private static final class CpBuilder {
        private final java.util.List<Object> entries = new java.util.ArrayList<>();

        CpBuilder() {
            entries.add(null); // 索引 0 占位
        }

        java.util.List<Object> entries() {return entries;}

        int u(String s) {entries.add(s); return entries.size() - 1;}

        int c(int utf8Idx) {entries.add(new int[]{utf8Idx, 0}); return entries.size() - 1;}

        int n(int nameIdx, int descIdx) {
            entries.add(new int[]{nameIdx, descIdx, 1});
            return entries.size() - 1;
        }

        int s(int utf8Idx) {entries.add(new int[]{utf8Idx, 2}); return entries.size() - 1;}

        /** CONSTANT_MethodHandle:先建 Methodref(Class + nameAndType),再建句柄. */
        int h(int refKind, int classIdx, int natIdx) {
            entries.add(new int[]{classIdx, natIdx, 5}); // Methodref
            int refIdx = entries.size() - 1;
            entries.add(new int[]{refKind, refIdx, 3});  // MethodHandle
            return entries.size() - 1;
        }

        int d(int bsmIdx, int natIdx) {
            entries.add(new int[]{bsmIdx, natIdx, 4});
            return entries.size() - 1;
        }
    }

    /** 写一个 {@code public static Object m() { return <ldc condy>; } } 方法.
     *  nameIdx/descIdx 均指向 Utf8 条目(方法表的索引语义,非 NameAndType). */
    private static void writeCondyMethod(java.io.DataOutputStream out, int nameIdx, int descIdx,
                                         int dynIdx, int uCode) throws Exception {
        out.writeShort(0x0009); // public static
        out.writeShort(nameIdx);
        out.writeShort(descIdx);
        out.writeShort(1);      // 1 个属性:Code
        out.writeShort(uCode);
        java.io.ByteArrayOutputStream codeBos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream code = new java.io.DataOutputStream(codeBos);
        code.writeShort(1); // max_stack
        code.writeShort(0); // max_locals
        byte[] insns = {0x12, (byte) dynIdx, (byte) 0xB0}; // ldc #dyn; areturn
        code.writeInt(insns.length); // code_length
        code.write(insns);
        code.writeShort(0);   // 无异常表
        code.writeShort(0);   // 无子属性
        byte[] codeBytes = codeBos.toByteArray();
        out.writeInt(codeBytes.length);
        out.write(codeBytes);
    }
}
