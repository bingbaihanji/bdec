package com.bingbaihanji.bdec.type;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TypeResolverTest {

    @Test
    public void testPrimitiveDescriptors() {
        assertEquals(TypeKind.INT, TypeResolver.parseFieldDescriptor("I").kind());
        assertEquals(TypeKind.LONG, TypeResolver.parseFieldDescriptor("J").kind());
    }

    @Test
    public void testClassDescriptor() {
        JavaType t = TypeResolver.parseFieldDescriptor("Ljava/lang/String;");
        assertEquals(TypeKind.CLASS, t.kind());
        assertEquals("java/lang/String", t.internalName());
    }

    @Test
    public void testArrayDescriptor() {
        JavaType t = TypeResolver.parseFieldDescriptor("[I");
        assertEquals(TypeKind.ARRAY, t.kind());
        assertEquals(1, t.arrayDimensions());
    }

    @Test
    public void testMethodDescriptor() {
        JavaType[] params = TypeResolver.parseMethodParameterTypes("(IJ)Ljava/lang/String;");
        assertEquals(2, params.length);
        assertEquals(TypeKind.INT, params[0].kind());
        assertEquals(TypeKind.LONG, params[1].kind());
    }

    @Test
    public void testSlotCount() {
        assertEquals(2, JavaType.LONG.slotCount());
        assertEquals(1, JavaType.INT.slotCount());
        assertEquals(0, JavaType.VOID.slotCount());
    }
}
