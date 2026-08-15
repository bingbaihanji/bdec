package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.type.TypeResolver;
import com.bingbaihanji.bdec.util.TypeText;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 第 20 轮:数组元素带泛型参数的签名覆盖——
 * {@code List<T>[]} 不再渲染为擦除的 {@code List[]}.
 *
 * <p>JavaType 的 ARRAY 节点新增元素类型存储(第 6 组件 element),
 * elementOf 优先取存储元素,displayName 经元素链递归;
 * 签名解析(parseMethodSignature/parseGenericType)已把带泛型参数
 * 的元素传给 JavaType.array(),AstBuilder 签名覆盖闸门经
 * isClassTypeParam(internalName 携带元素链首个类型变量名)自动放行.</p>
 */
public class GenericArraySignatureRoundTripTest {

    @Test
    public void testGenericArrayMethodParamAndReturn() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class S {\n"
                        + "    static <T> List<T>[] f(List<T>[] a) { return a; }\n"
                        + "}\n",
                "S");
        DecompileTestHarness.assertContains(output,
                "static <T> List<T>[] f(List<T>[] a)");
        DecompileTestHarness.assertNotContains(output, "List[]");
    }

    @Test
    public void testMultiDimGenericArrayMethod() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "class M {\n"
                        + "    static <T> List<T>[][] g(List<T>[][] a) { return a; }\n"
                        + "}\n",
                "M");
        DecompileTestHarness.assertContains(output,
                "static <T> List<T>[][] g(List<T>[][] a)");
        DecompileTestHarness.assertNotContains(output, "List[][]");
    }

    /**
     * 具体类型实参(非类型变量)的泛型数组签名覆盖——
     * {@code List<String>[]} 不得擦除为 {@code List[]}.
     * 覆盖闸门 hasGenericsOrWildcard 需递归 ARRAY 元素(元素本身携带
     * 泛型实参),否则无类型变量的 {@code List<String>[]} 因顶层
     * typeArguments 为空而被跳过.
     */
    @Test
    public void testConcreteTypeArgArrayMethod() throws Exception {
        String output = DecompileTestHarness.decompileWithInnerLoader(
                "import java.util.List;\n"
                        + "class G {\n"
                        + "    List<String>[] m(List<String>[] p) { return p; }\n"
                        + "}\n",
                "G");
        DecompileTestHarness.assertContains(output, "List<String>[] m(List<String>[] p)");
        DecompileTestHarness.assertNotContains(output, "List[]");
        DecompileTestHarness.assertRecompiles(output, "G", java.util.Map.of());
    }

    @Test
    public void testNestedGenericArrayField() throws Exception {
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "import java.util.List;\n"
                        + "import java.util.Map;\n"
                        + "class C<T> {\n"
                        + "    Map<String, List<T>[]> m;\n"
                        + "}\n",
                "C");
        DecompileTestHarness.assertContains(output,
                "Map<String, ", "List<T>[]> m;");
        DecompileTestHarness.assertNotContains(output, "List[]>");
    }

    @Test
    public void testTypeVarArrayMethodRegression() throws Exception {
        // Round 19 行为锁定:裸 T[] 方法参数/返回仍渲染为 T[] 而非 Object[]
        DecompileTestHarness h = new DecompileTestHarness();
        String output = h.decompileSource(
                "class S {\n"
                        + "    static <T> T[] make(T[] a) { return a; }\n"
                        + "}\n",
                "S");
        DecompileTestHarness.assertContains(output, "<T> T[] make(T[] a)");
        DecompileTestHarness.assertNotContains(output, "Object[]");
    }

    // ── JavaType 单元级契约 ─────────────────────────────────────────

    @Test
    public void testElementOfPreservesGenericElement() {
        JavaType listOfT = new JavaType(TypeKind.CLASS, "java/util/List",
                "Ljava/util/List;", List.of(JavaType.typeVariable("T")), 0);
        JavaType arr = JavaType.array(listOfT, 1);
        // descriptor 保持字节级不变
        assertEquals("[Ljava/util/List;", arr.descriptor());
        assertEquals(1, arr.arrayDimensions());
        JavaType elem = JavaType.elementOf(arr);
        assertEquals(TypeKind.CLASS, elem.kind());
        assertEquals("java/util/List", elem.internalName());
        assertEquals(1, elem.typeArguments().size());
        assertEquals(TypeKind.TYPE_VARIABLE, elem.typeArguments().get(0).kind());
        assertEquals("T", elem.typeArguments().get(0).internalName());
        // displayName 与旧行为一致:非 java.lang 类全限定
        assertEquals("java.util.List<T>[]", arr.displayName());
    }

    @Test
    public void testDisplayNameMultiDimGenericArray() {
        JavaType listOfT = new JavaType(TypeKind.CLASS, "java/util/List",
                "Ljava/util/List;", List.of(JavaType.typeVariable("T")), 0);
        // 扁平创建(dimensions 一次给全)
        assertEquals("java.util.List<T>[][]", JavaType.array(listOfT, 2).displayName());
        // 嵌套创建(SignatureParser 每层 1 维)
        JavaType nested = JavaType.array(JavaType.array(listOfT, 1), 1);
        assertEquals("java.util.List<T>[][]", nested.displayName());
        assertEquals(TypeKind.ARRAY, JavaType.elementOf(nested).kind());
        // TypeResolver 混合形态(外层维度累积)displayName 不受回归
        JavaType tr = TypeResolver.parseFieldDescriptor("[[Ljava/lang/String;");
        assertEquals("String[][]", tr.displayName());
    }

    /**
     * TypeText.render(重写器合成类型路径)对 TypeResolver 维度累积形态
     * 多维数组的括号数不得翻倍——修复前 {@code [[String} 渲染为
     * {@code String[][][]}(多一层),3 维 {@code [[[String} 渲染为
     * 6 个括号.与 {@code displayName}/{@code arrayBaseName} 的
     * {@code remaining = max(1, dims - element.dims)} 补差一致.
     */
    @Test
    public void testTypeTextRenderCumulativeDimsBracketCount() {
        Set<String> imports = new HashSet<>();
        // [[Ljava/lang/String; → ARRAY(ARRAY(String,1),2) 累积形态
        JavaType t2 = TypeResolver.parseFieldDescriptor("[[Ljava/lang/String;");
        assertEquals("String[][]",
                TypeText.render(t2, "", Map.of(), imports));
        // [[[Ljava/lang/String; → 3 维,每层补差不得翻倍
        JavaType t3 = TypeResolver.parseFieldDescriptor("[[[Ljava/lang/String;");
        assertEquals("String[][][]",
                TypeText.render(t3, "", Map.of(), imports));
        // 基本数组不变
        JavaType t1 = TypeResolver.parseFieldDescriptor("[Ljava/lang/String;");
        assertEquals("String[]",
                TypeText.render(t1, "", Map.of(), imports));
        // 基本类型数组也不变
        JavaType ti = TypeResolver.parseFieldDescriptor("[[I");
        assertEquals("int[][]",
                TypeText.render(ti, "", Map.of(), imports));
    }

    @Test
    public void testArrayInternalNameOnlyForTypeVariableElements() {
        // 元素链无类型变量的数组:internalName 保持 null(闸门不误开)
        JavaType plain = JavaType.array(JavaType.classType("java/lang/String"), 1);
        assertNull(plain.internalName());
        // 元素链含类型变量的数组:internalName 携带首个类型变量名,
        // 供 AstBuilder 签名覆盖闸门(isClassTypeParam)放行
        JavaType listOfT = new JavaType(TypeKind.CLASS, "java/util/List",
                "Ljava/util/List;", List.of(JavaType.typeVariable("T")), 0);
        assertEquals("T", JavaType.array(listOfT, 1).internalName());
        JavaType nestedT = JavaType.array(
                new JavaType(TypeKind.CLASS, "java/util/List",
                        "Ljava/util/List;",
                        List.of(JavaType.array(JavaType.typeVariable("U"), 1)), 0),
                1);
        assertEquals("U", nestedT.internalName());
    }
}
