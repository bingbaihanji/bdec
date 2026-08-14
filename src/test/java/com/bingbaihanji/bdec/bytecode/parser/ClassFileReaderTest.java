package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClassFileReaderTest {

    @Test
    public void testParseObjectClass() throws Exception {
        byte[] bytes = readRuntimeClass("/java/lang/Object.class");

        ClassFileReader reader = new ClassFileReader();
        ClassFileModel model = reader.read("java/lang/Object", bytes);

        assertEquals("java/lang/Object", model.internalName());
        assertNull("Object has no superclass", model.superInternalName());
        assertTrue("Major version >= 45", model.majorVersion() >= 45);
        assertTrue("Should have methods", model.methods().size() > 0);

        boolean hasHashCode = model.methods().stream()
                .anyMatch(m -> m.name().equals("hashCode") && m.descriptor().equals("()I"));
        assertTrue("Object should have hashCode()", hasHashCode);
    }

    @Test
    public void testParseStringClass() throws Exception {
        byte[] bytes = readRuntimeClass("/java/lang/String.class");

        ClassFileReader reader = new ClassFileReader();
        ClassFileModel model = reader.read("java/lang/String", bytes);

        assertEquals("java/lang/String", model.internalName());
        assertTrue("String has many methods", model.methods().size() > 10);
    }

    /** 从运行时映像加载 JDK 类字节(不依赖文件系统布局,JDK/JRE 均可用). */
    private byte[] readRuntimeClass(String resource) throws Exception {
        var in = Object.class.getResourceAsStream(resource);
        assertNotNull("runtime class not loadable: " + resource, in);
        return in.readAllBytes();
    }
}
