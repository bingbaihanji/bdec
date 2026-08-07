package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpClass;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpLong;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry.CpUtf8;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConstantPoolParserTest {

    @Test
    public void testParseMinimalPool() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeShort(3);  // cp_count = 3
        dos.writeByte(1);   // UTF8
        dos.writeUTF("java/lang/Object");
        dos.writeByte(7);   // Class
        dos.writeShort(1);  // name_index = 1

        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        DataInputStream dis = new DataInputStream(bis);

        ConstantPoolEntry[] pool = new ConstantPoolParser().parse(dis);

        assertEquals(3, pool.length);
        assertNull(pool[0]);
        assertTrue(pool[1] instanceof CpUtf8);
        assertEquals("java/lang/Object", ((CpUtf8) pool[1]).value());
        assertTrue(pool[2] instanceof CpClass);
        assertEquals(1, ((CpClass) pool[2]).nameIndex());
    }

    @Test
    public void testLongDoubleTakeTwoIndices() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeShort(4);  // cp_count = 4 (indices 1,2,3; index 0 reserved)
        dos.writeByte(5);   // Long (takes indices 1 and 2)
        dos.writeLong(42L);
        dos.writeByte(1);   // UTF8 at index 3
        dos.writeUTF("hello");

        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        DataInputStream dis = new DataInputStream(bis);

        ConstantPoolEntry[] pool = new ConstantPoolParser().parse(dis);

        assertEquals(4, pool.length);
        assertTrue(pool[1] instanceof CpLong);
        assertEquals(42L, ((CpLong) pool[1]).value());
        assertNull(pool[2]); // index 2 is unusable
        assertTrue(pool[3] instanceof CpUtf8);
        assertEquals("hello", ((CpUtf8) pool[3]).value());
    }

    @Test
    public void testUtf8Helper() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeShort(2);
        dos.writeByte(1);
        dos.writeUTF("testString");

        var pool = new ConstantPoolParser().parse(new DataInputStream(
                new ByteArrayInputStream(bos.toByteArray())));

        assertEquals("testString", ConstantPoolParser.utf8(pool, 1));
    }
}
