package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.bytecode.model.TypePathElement;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.util.ClassNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 类型引用渲染与 import 收集工具(里程碑 Phase 3).
 *
 * <p>从 {@link AstBuilder} 中提取,承载父类/接口泛型引用渲染(含 JSR-308
 * 类型路径注解注入)与 import 收集约定.纯静态方法,无实例状态.</p>
 */
public final class TypeReferenceUtil {

    private TypeReferenceUtil() {
    }

    /**
     * 从内部名称提取简单类名(无内部类信息).
     *
     * @param internal 类的内部名称(如 "com/example/MyClass")
     * @return 提取的简单类名
     */
    public static String simpleName(String internal) {
        int idx = internal.lastIndexOf('/');
        return idx >= 0 ? internal.substring(idx + 1) : internal;
    }

    /**
     * 将签名解析出的父类/接口类型渲染为源码引用名.
     * <ul>
     *   <li>根节点(父类/接口自身)用简单名(依赖 import),维持原有约定;</li>
     *   <li>泛型实参递归渲染为短名并收集 import(与字段/方法类型渲染的
     *       java.lang/同包过滤,$→. 转换约定一致);</li>
     *   <li>渲染时按类型路径在 TYPE_ARGUMENT 位置注入 JSR-308 类型注解
     *       (如 {@code Base<@A String>} 中的 {@code @A}).</li>
     * </ul>
     *
     * @param t          父类/接口类型
     * @param annsByPath 类型路径 → 渲染后注解行的映射(路径相对该父类型根,
     *                   可为 null 或空)
     * @param imports    待填充的 import 集合
     * @param thisClass  当前类的简单名称(避免自引用 import)
     * @return 源码引用名
     */
    public static String renderClassRef(
            JavaType t,
            Map<List<TypePathElement>, List<String>> annsByPath,
            Set<String> imports, String thisClass) {
        return renderClassRefAtPath(t, List.of(), annsByPath, imports, thisClass);
    }

    /** renderClassRef 的递归实现:path 为当前节点在父类型树中的类型路径 */
    private static String renderClassRefAtPath(
            JavaType t,
            List<TypePathElement> path,
            Map<List<TypePathElement>, List<String>> annsByPath,
            Set<String> imports, String thisClass) {
        if (t == null) {
            return "Object";
        }
        StringBuilder sb = new StringBuilder();
        // 路径处注解(如 [TYPE_ARGUMENT(0)] → Base<@A String> 中的 @A)
        if (annsByPath != null && !annsByPath.isEmpty()) {
            for (String a : annsByPath.getOrDefault(path, List.of())) {
                sb.append(a).append(' ');
            }
        }
        switch (t.kind()) {
            case CLASS -> {
                String internal = t.internalName();
                String simple = internal != null ? simpleName(internal) : "Object";
                if (path.isEmpty()) {
                    // 根节点:维持原有简单名渲染,与调用处的
                    // collectImport(JavaType.classType(...)) 配合
                    sb.append(simple);
                } else {
                    // 泛型实参:沿用字段类型的短名 + import 收集约定
                    if (!ClassNames.isAnonymousClassName(simple)) {
                        simple = simple.replace('$', '.');
                    }
                    sb.append(simple);
                    collectImport(t, imports, thisClass);
                }
                if (!t.typeArguments().isEmpty()) {
                    sb.append('<');
                    for (int i = 0; i < t.typeArguments().size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(renderClassRefAtPath(t.typeArguments().get(i),
                                appendTypeArgument(path, i), annsByPath, imports, thisClass));
                    }
                    sb.append('>');
                }
            }
            case WILDCARD -> {
                // 通配符(合法类签名的父类型实参不允许,防御手写字节码):
                // 边界递归渲染短名并收集 import
                String boundRendered = null;
                if (!t.typeArguments().isEmpty()) {
                    boundRendered = renderClassRefAtPath(t.typeArguments().getFirst(),
                            path, annsByPath, imports, thisClass);
                }
                if (t.internalName() != null && t.internalName().startsWith("? super ")) {
                    sb.append("? super ").append(boundRendered != null ? boundRendered : "Object");
                } else if (t.internalName() != null
                        && t.internalName().startsWith("? extends ")) {
                    sb.append("? extends ").append(boundRendered != null ? boundRendered : "Object");
                } else {
                    sb.append('?');
                }
            }
            case ARRAY -> {
                JavaType elem = JavaType.elementOf(t);
                String base = renderClassRefAtPath(elem, path, annsByPath, imports, thisClass);
                if (elem != null && elem.kind() == TypeKind.ARRAY) {
                    // TypeResolver 维度累积形态:元素递归已含内层括号,仅补外层差值
                    int remaining = Math.max(1,
                            t.arrayDimensions() - elem.arrayDimensions());
                    sb.append(base).append("[]".repeat(remaining));
                } else {
                    sb.append(base).append("[]".repeat(t.arrayDimensions()));
                }
            }
            default -> sb.append(t.displayName());
        }
        return sb.toString();
    }

    /** 在类型路径末尾追加 TYPE_ARGUMENT(i) 元素(不可变列表) */
    private static List<TypePathElement> appendTypeArgument(
            List<TypePathElement> path, int index) {
        List<TypePathElement> next = new ArrayList<>(path);
        next.add(new TypePathElement(TypePathElement.KIND_TYPE_ARGUMENT, index));
        return next;
    }

    /** 类型路径是否为纯 TYPE_ARGUMENT 链(无数组/内部类型/通配符边界等元素) */
    public static boolean isTypeArgumentPath(List<TypePathElement> path) {
        for (var e : path) {
            if (e.kind() != TypePathElement.KIND_TYPE_ARGUMENT) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查一个 JavaType 是否表示指定类类型参数列表中的类型变量.
     * 类型变量在签名中以 {@code TName;} 格式出现,由 SignatureParser
     * 统一解析为 kind=TYPE_VARIABLE;internalName 携带变量名,故按名字匹配.
     */
    public static boolean isClassTypeParam(JavaType type, List<String> classTypeParams) {
        if (type == null || classTypeParams.isEmpty()) {
            return false;
        }
        // 类型变量以内部名称携带变量名(如 "T","U"),与 kind 无关
        String name = type.internalName();
        return name != null && classTypeParams.contains(name);
    }

    /** 类型是否携带泛型参数或通配符(签名比擦除描述符信息更丰富).
     *  描述符是擦除后的,永不携带类型参数——只要签名类型有类型参数
     *  (如 Function&lt;Integer, Integer&gt;,List&lt;String&gt;),签名就更丰富,
     *  必须替换.此前要求参数自身嵌套泛型,导致扁平泛型
     *  (Function&lt;Integer,Integer&gt;)被错误跳过,输出擦除的原始类型. */
    public static boolean hasGenericsOrWildcard(JavaType t) {
        if (t == null) {
            return false;
        }
        if (!t.typeArguments().isEmpty()) {
            return true;
        }
        // ARRAY 的泛型信息位于元素类型(如 List<String>[]):
        // 签名比擦除的 List[] 更丰富,须递归元素判定.
        if (t.kind() == TypeKind.ARRAY && t.element() != null) {
            return hasGenericsOrWildcard(t.element());
        }
        return false;
    }

    /**
     * 数组的基元素是否为签名格式的类型变量(裸 {@code T[]}).
     * 签名中 {@code T[]} 解析为 kind=ARRAY,descriptor={@code [TT;}
     * (internalName 恒 null,typeArguments 恒空,前两个闸门条件对 ARRAY
     * 全部拒绝),故按描述符判定:逐层剥掉前导 {@code [} 后剩余部分是
     * {@code "T" + 名字 + ";"} 形态即为类型变量数组,签名比擦除的
     * {@code Object[]} 更丰富,必须替换.渲染端经 elementOf →
     * fromDescriptor 已修复为产 TYPE_VARIABLE,输出 {@code T[]}.
     */
    public static boolean hasTypeVariableArrayElement(JavaType t) {
        if (t == null || t.kind() != TypeKind.ARRAY || t.descriptor() == null) {
            return false;
        }
        String baseDesc = t.descriptor().replaceFirst("^\\[+", "");
        return baseDesc.length() > 2 && baseDesc.charAt(0) == 'T'
                && baseDesc.charAt(baseDesc.length() - 1) == ';';
    }

    /**
     * 为指定类型收集import条目(如果满足导入条件).
     * <ul>
     *   <li>CLASS 类型:收集自身后递归泛型实参(字段渲染已输出短名,
     *       实参的 import 必须一并收集,否则 javac 重编译失败);</li>
     *   <li>ARRAY 类型:递归元素类型({@code JavaType.elementOf} 剥离一维);</li>
     *   <li>WILDCARD 类型:递归边界({@code ? extends X} 的 X 若可导入需收集);</li>
     *   <li>类型变量(kind=TYPE_VARIABLE)与基本类型不产生 import.</li>
     * </ul>
     * 仅当类型为CLASS类型且来自不同包(非当前类)时才添加到import集合中.
     * java.lang 直接成员与同包类型由 build() 末尾的 import 列表过滤统一剔除;
     * 方法签名路径的旧 CLASS 伪装类型变量(内部名如 "T")由"无点号过滤"兜底剔除.
     *
     * @param type      需要检查的Java类型
     * @param imports   待填充的import集合
     * @param thisClass 当前类的简单名称
     */
    public static void collectImport(JavaType type, Set<String> imports, String thisClass) {
        if (type == null) {
            return;
        }
        switch (type.kind()) {
            case CLASS -> {
                String internalName = type.internalName();
                if (internalName != null && !simpleName(internalName).equals(thisClass)) {
                    String dotted = internalName.replace('/', '.');
                    // 对命名内部类将 $ 转换为 .(如 Map$Entry → Map.Entry),
                    // 但跳过匿名类(如 TestClass2$1——数字开头的"名称"非法)
                    if (ClassNames.isAnonymousClassName(simpleName(internalName))) {
                        return; // 匿名类不导入
                    }
                    dotted = dotted.replace('$', '.');
                    imports.add(dotted);
                }
                // 递归泛型实参(字段渲染走 import 感知短名,实参同样需要 import)
                for (JavaType arg : type.typeArguments()) {
                    collectImport(arg, imports, thisClass);
                }
            }
            case ARRAY -> collectImport(JavaType.elementOf(type), imports, thisClass);
            case WILDCARD -> {
                // 递归通配符边界(? extends X / ? super Y 的 X,Y 若可导入需收集)
                for (JavaType bound : type.typeArguments()) {
                    collectImport(bound, imports, thisClass);
                }
            }
            default -> {
                // TYPE_VARIABLE 与基本类型:无 import
            }
        }
    }
}
