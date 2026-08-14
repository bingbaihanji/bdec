package com.bingbaihanji.bdec.util;

import com.bingbaihanji.bdec.type.JavaType;

import java.util.Map;
import java.util.Set;

/**
 * 类型名渲染门面(里程碑 Phase 2.1).
 *
 * <p>统一散落在 {@code util/TypeText} 与 {@code emit/ExpressionEmitter} 两套
 * 类型渲染路径中的 CLASS 短名解析规则,建立单一事实源:</p>
 * <ul>
 *   <li>内部名称 {@code $} → {@code .}(匿名类除外),内部类友好名称映射优先</li>
 *   <li>{@code java.lang} 直接成员去前缀</li>
 *   <li>同包类型 → 简单名</li>
 *   <li>其余类型按"收集模式"或"给定 import 模式"二选一处理</li>
 * </ul>
 *
 * <p>两种模式:</p>
 * <ul>
 *   <li><b>收集模式</b>({@link #render}):重写器合成类型,渲染短名并把全限定名
 *       收集进 {@code collectedImports},由调用方合并进编译单元的 import 列表</li>
 *   <li><b>给定模式</b>({@link #className}):发射器已定 import 列表,短名仅在
 *       全限定名恰在 {@code importedFqns} 中时才渲染,否则回退全限定名</li>
 * </ul>
 */
public final class TypeNameRenderer {

    private TypeNameRenderer() {
    }

    /**
     * 渲染 JavaType 树为 import 感知的源码类型文本(收集模式).
     *
     * @param type             要渲染的类型
     * @param currentPackage   当前编译单元的包名(默认包为 "")
     * @param innerClassNames  内部类友好名称映射(可为空)
     * @param collectedImports 需收集的 import 全限定名集合(可为 null,表示不收集)
     * @return 渲染后的类型文本
     */
    public static String render(JavaType type, String currentPackage,
                                Map<String, String> innerClassNames,
                                Set<String> collectedImports) {
        return renderInternal(type, pkg(currentPackage), innerClassNames,
                collectedImports, null);
    }

    /**
     * 渲染 CLASS 类型的基础短名(不含泛型实参,给定 import 模式).
     *
     * @param type            要渲染的 CLASS 类型
     * @param currentPackage  当前编译单元的包名
     * @param innerClassNames 内部类友好名称映射(可为空)
     * @param importedFqns    已导入的全限定名集合(精确匹配)
     * @return 基础名:全限定名恰在 import 列表中时渲染短名,否则回退全限定名
     */
    public static String className(JavaType type, String currentPackage,
                                   Map<String, String> innerClassNames,
                                   Set<String> importedFqns) {
        return resolveClass(type, pkg(currentPackage), innerClassNames,
                null, importedFqns);
    }

    /** 规范化包名为非 null(默认包为 ""). */
    private static String pkg(String currentPackage) {
        return currentPackage != null ? currentPackage : "";
    }

    /** 递归渲染 JavaType 树(基本类型/类/数组/通配符/类型变量). */
    private static String renderInternal(JavaType type, String pkg,
                                         Map<String, String> inner,
                                         Set<String> collected,
                                         Set<String> importedFqns) {
        if (type == null) {
            return "Object";
        }
        return switch (type.kind()) {
            case VOID -> "void";
            case BOOLEAN -> "boolean";
            case BYTE -> "byte";
            case SHORT -> "short";
            case CHAR -> "char";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case CLASS -> renderClass(type, pkg, inner, collected, importedFqns);
            case ARRAY -> {
                JavaType elem = JavaType.elementOf(type);
                yield renderInternal(elem, pkg, inner, collected, importedFqns)
                        + "[]".repeat(Math.max(0, type.arrayDimensions()));
            }
            case WILDCARD -> {
                String bound = !type.typeArguments().isEmpty()
                        ? renderInternal(type.typeArguments().getFirst(), pkg,
                        inner, collected, importedFqns)
                        : null;
                if (type.internalName() != null && type.internalName().startsWith("? super ")) {
                    yield "? super " + (bound != null ? bound : "Object");
                }
                if (type.internalName() != null
                        && type.internalName().startsWith("? extends ")) {
                    yield "? extends " + (bound != null ? bound : "Object");
                }
                yield "?";
            }
            case TYPE_VARIABLE -> type.internalName() != null ? type.internalName() : "?";
            default -> type.descriptor();
        };
    }

    /** 渲染 CLASS 类型:短名 + 泛型实参递归 + import 收集(收集模式). */
    private static String renderClass(JavaType type, String pkg,
                                      Map<String, String> inner,
                                      Set<String> collected,
                                      Set<String> importedFqns) {
        return appendTypeArgs(
                resolveClass(type, pkg, inner, collected, importedFqns),
                type, pkg, inner, collected, importedFqns);
    }

    /** 解析 CLASS 类型的基础名(不含泛型实参),两种模式共享. */
    private static String resolveClass(JavaType type, String pkg,
                                       Map<String, String> inner,
                                       Set<String> collected,
                                       Set<String> importedFqns) {
        String internal = type.internalName();
        if (internal == null) {
            return "Object";
        }
        String dotted = internal.replace('/', '.');
        String rawSimple = internal.substring(internal.lastIndexOf('/') + 1);

        // 内部类友好名称(同文件内部类,如 TestClass2$1LocalClass → LocalClass)
        if (inner != null && inner.containsKey(rawSimple)) {
            String friendly = inner.get(rawSimple);
            if (friendly != null && !friendly.isEmpty()) {
                return friendly;
            }
        }

        // 匿名类($ 后跟数字)不可作为源码类型名导入,防御性原样输出
        if (ClassNames.isAnonymousClassName(rawSimple)) {
            return rawSimple;
        }

        // java.lang 直接成员无需导入且自动可见
        if (dotted.startsWith("java.lang.")
                && dotted.indexOf('.', "java.lang.".length()) < 0) {
            return dotted.substring("java.lang.".length());
        }

        String simple = rawSimple.replace('$', '.');

        // 同包类型无需导入
        int lastSlash = internal.lastIndexOf('/');
        String typePkg = lastSlash >= 0
                ? internal.substring(0, lastSlash).replace('/', '.') : "";
        if (typePkg.equals(pkg)) {
            return simple;
        }

        String fq = dotted.replace('$', '.');
        // 收集模式:短名渲染并收集全限定名 import
        if (collected != null) {
            collected.add(fq);
            return simple;
        }
        // 给定模式:全限定名恰在 import 列表中才用短名,否则回退全限定名
        if (importedFqns != null) {
            return importedFqns.contains(fq) ? simple : fq;
        }
        // 既不收集也不给定(旧 TypeText 传 null collected 语义):短名不收集
        return simple;
    }

    /** 渲染泛型实参列表(如 {@code Box<Map<String, Integer>>}),无实参直接返回基础名. */
    private static String appendTypeArgs(String base, JavaType type, String pkg,
                                         Map<String, String> inner,
                                         Set<String> collected,
                                         Set<String> importedFqns) {
        if (type.typeArguments().isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        sb.append('<');
        for (int i = 0; i < type.typeArguments().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderInternal(type.typeArguments().get(i), pkg,
                    inner, collected, importedFqns));
        }
        sb.append('>');
        return sb.toString();
    }
}
