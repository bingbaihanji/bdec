package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.GenericMethodResolver;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * GenericMethodResolver 单元测试:调用点实参 + 方法签名绑定推断泛型返回类型.
 */
public class GenericMethodResolverTest {

    private static JavaType cls(String internal) {
        return JavaType.classType(internal);
    }

    private static JavaType array(String internal) {
        return JavaType.array(JavaType.classType(internal), 1);
    }

    private static ConstantValue val(Object v, JavaType t) {
        return new ConstantValue(v, t);
    }

    @Test
    public void testMapOfBindsKAndV() {
        // Map.of("a", 1) → 签名 <K,V> Map<K,V> of(K,V),实参 [String, Integer]
        JavaType raw = cls("java/util/Map");
        JavaType result = GenericMethodResolver.inferGenericReturnType(
                "java/util/Map", "of", raw,
                new JavaType[]{cls("java/lang/Object"), cls("java/lang/Object")},
                List.of(val("a", cls("java/lang/String")),
                        val(1, cls("java/lang/Integer"))));
        assertEquals("java/util/Map", result.internalName());
        assertEquals(2, result.typeArguments().size());
        assertEquals("java/lang/String", result.typeArguments().get(0).internalName());
        assertEquals("java/lang/Integer", result.typeArguments().get(1).internalName());
    }

    @Test
    public void testListOfFixedArityBindsE() {
        // List.of("a","b") → 固定元数 <E> List<E> of(E,E),实参 [String, String]
        JavaType raw = cls("java/util/List");
        JavaType result = GenericMethodResolver.inferGenericReturnType(
                "java/util/List", "of", raw,
                new JavaType[]{cls("java/lang/Object"), cls("java/lang/Object")},
                List.of(val("a", cls("java/lang/String")),
                        val("b", cls("java/lang/String"))));
        assertEquals(1, result.typeArguments().size());
        assertEquals("java/lang/String", result.typeArguments().get(0).internalName());
    }

    @Test
    public void testArraysAsListArrayElement() {
        // Arrays.asList(String[]) → <T> List<T> asList(T...),实参 String[] → List<String>
        JavaType raw = cls("java/util/List");
        JavaType result = GenericMethodResolver.inferGenericReturnType(
                "java/util/Arrays", "asList", raw,
                new JavaType[]{array("java/lang/Object")},
                List.of(val(null, array("java/lang/String"))));
        assertEquals(1, result.typeArguments().size());
        assertEquals("java/lang/String", result.typeArguments().get(0).internalName());
    }

    @Test
    public void testEmptyListUnboundFallsBackToRaw() {
        // Collections.emptyList() → <T> List<T>,无实参 T 未绑定 → 回退原始类型
        JavaType raw = cls("java/util/List");
        JavaType result = GenericMethodResolver.inferGenericReturnType(
                "java/util/Collections", "emptyList", raw,
                new JavaType[0], List.of());
        assertEquals("java/util/List", result.internalName());
        assertEquals(0, result.typeArguments().size());
    }

    @Test
    public void testNonJdkClassUntouched() {
        // 用户类不推断
        JavaType raw = cls("com/example/Foo");
        JavaType result = GenericMethodResolver.inferGenericReturnType(
                "com/example/Foo", "bar", raw,
                new JavaType[0], List.of());
        assertEquals(raw, result);
    }

    @Test
    public void testMissingMethodFallsBack() {
        // 不存在的方法 → 回退原始类型(反射失败不崩溃)
        JavaType raw = cls("java/util/List");
        JavaType result = GenericMethodResolver.inferGenericReturnType(
                "java/util/List", "noSuchMethod", raw,
                new JavaType[0], List.of());
        assertEquals(raw, result);
    }
}
