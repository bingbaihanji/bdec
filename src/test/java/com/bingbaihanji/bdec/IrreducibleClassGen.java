package com.bingbaihanji.bdec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 生成含不可归约控制流的测试类 {@code Irr.class}.
 *
 * <p>方法 {@code static int m(int x)} 的 CFG:
 * <pre>
 *   A: if x&gt;0 goto B else goto C
 *   B: goto D
 *   C: goto D          (C 有 A,D 两个前驱 —— 不可归约)
 *   D: ...; if x&gt;0 goto C   (D 回跳 C)
 *   E: return
 * </pre>
 * 循环 {C, D} 有多个入口(A→C,D→C),不可归约.</p>
 */
public final class IrreducibleClassGen {

    private IrreducibleClassGen() {}

    /** 生成 Irr.class 字节. */
    public static byte[] irrClassBytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);

        // === 常量池 ===
        // 1=Class Irr, 2=Utf8 "Irr", 3=Class Object, 4=Utf8 "java/lang/Object",
        // 5=Utf8 "m", 6=Utf8 "(I)I", 7=Utf8 "Code", 8=Utf8 "x"
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);   // minor
        out.writeShort(52);  // major Java 8
        out.writeShort(9);   // constant_pool_count (8 entries + 1)
        // #1 Class
        out.writeByte(7);
        out.writeShort(2);
        // #2 Utf8 "Irr"
        out.writeByte(1);
        out.writeUTF("Irr");
        // #3 Class
        out.writeByte(7);
        out.writeShort(4);
        // #4 Utf8
        out.writeByte(1);
        out.writeUTF("java/lang/Object");
        // #5 Utf8 "m"
        out.writeByte(1);
        out.writeUTF("m");
        // #6 Utf8 "(I)I"
        out.writeByte(1);
        out.writeUTF("(I)I");
        // #7 Utf8 "Code"
        out.writeByte(1);
        out.writeUTF("Code");
        // #8 Utf8 "x"
        out.writeByte(1);
        out.writeUTF("x");

        out.writeShort(0x0021); // access: public super
        out.writeShort(1);      // this_class = #1
        out.writeShort(3);      // super_class = #3
        out.writeShort(0);      // interfaces
        out.writeShort(0);      // fields
        out.writeShort(1);      // methods_count

        // === 方法 m(I)I ===
        out.writeShort(0x0009); // public static
        out.writeShort(5);      // name = m
        out.writeShort(6);      // descriptor = (I)I
        out.writeShort(1);      // attributes_count = 1 (Code)

        // === Code 属性 ===
        byte[] code = {
                (byte) 0x03,                  // iconst_0
                (byte) 0x3c,                  // istore_1  (r = 0)
                (byte) 0x1a,                  // iload_0
                (byte) 0x9a, 0x00, 0x06,      // ifgt 9  (A: x>0 → B@9)
                (byte) 0xa7, 0x00, 0x06,      // goto 12 (C → D@12)
                (byte) 0xa7, 0x00, 0x03,      // goto 12 (B → D@12)
                (byte) 0x1b,                  // iload_1
                (byte) 0x04,                  // iconst_1
                (byte) 0x60,                  // iadd
                (byte) 0x3c,                  // istore_1  (r++)
                (byte) 0x1a,                  // iload_0
                (byte) 0x9a, (byte) 0xff, (byte) 0xf5, // ifgt 6  (D: x>0 → C@6)
                (byte) 0x1b,                  // iload_1
                (byte) 0xac                   // ireturn
        };
        out.writeShort(7);      // attribute_name = Code
        out.writeInt(12 + code.length); // attribute_length (max_stack, max_locals, code_len, code)
        out.writeShort(2);      // max_stack
        out.writeShort(2);      // max_locals
        out.writeInt(code.length);
        out.write(code);
        out.writeShort(0);      // exception_table_length
        out.writeShort(0);      // attributes_count

        out.writeShort(0);      // class attributes_count
        out.flush();
        return bos.toByteArray();
    }

    /** 测试入口:写出 Irr.class 到指定目录. */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        Files.write(dir.resolve("Irr.class"), irrClassBytes());
        System.out.println("written: " + dir.resolve("Irr.class"));
    }
}
