package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 签名属性解析器(最小化实现).
 *
 * <p>解析泛型类型签名({@code Signature} 属性),提取类型参数名称
 * 并重建泛型显示名称.支持常见的泛型模式:
 * <ul>
 *   <li>类型参数声明({@code <E:Ljava/lang/Object;>})</li>
 *   <li>泛型类/接口类型({@code Ljava/util/List<Ljava/lang/String;>;})</li>
 *   <li>有界类型参数(继承/超类通配符)</li>
 *   <li>无界通配符({@code *})</li>
 *   <li>数组类型</li>
 * </ul>
 *
 * <p>该类为工具类,仅包含静态方法,不可实例化.
 */
public final class SignatureParser {

    /** 私有构造函数,防止外部实例化工具类. */
    private SignatureParser() {}

    /**
     * 从类签名中提取形式类型参数名称.
     *
     * <p>例如:{@code <E:Ljava/lang/Object;>Ljava/util/AbstractQueue<TE;>;}
     * → 返回 {@code ["E"]}
     *
     * @param signature 类签名属性字符串
     * @return 类型参数名称列表
     */
    public static List<String> extractTypeParams(String signature) {
        List<String> params = new ArrayList<>();
        if (signature == null || signature.isEmpty() || !signature.startsWith("<")) {
            return params;
        }
        int i = 1; // 跳过 '<'
        while (i < signature.length() && signature.charAt(i) != '>') {
            // 读取类型参数名称直到冒号
            int colon = signature.indexOf(':', i);
            if (colon < 0) {
                break;
            }
            String name = signature.substring(i, colon);
            params.add(name);
            // 跳过类边界与接口边界描述
            i = colon + 1;
            // 跳过边界:L...; 或 T...; 或 [ (数组)
            while (i < signature.length() && signature.charAt(i) != ':'
                    && signature.charAt(i) != '>') {
                char c = signature.charAt(i);
                if (c == 'L' || c == 'T') {
                    int semi = signature.indexOf(';', i);
                    if (semi < 0) {
                        i = signature.length();
                        break;
                    }
                    i = semi + 1;
                } else if (c == '[') {
                    i++; // 跳过'[', 后续一定是 L...; 或基元类型, 由下一次迭代处理
                } else {
                    // 不是类型签名的一部分 → 下一个类型参数即将开始
                    break;
                }
            }
            // 跳过 ':' 分隔符
            if (i < signature.length() && signature.charAt(i) == ':') {
                i++;
            }
        }
        return params;
    }

    /**
     * 从方法签名中提取方法级类型参数.
     *
     * <p>例如:{@code <T:Ljava/lang/Object;>(TT;)TT;}
     * → 返回 {@code ["T"]}
     *
     * <p>格式与类签名相同:以 {@code <...>} 开头.
     *
     * @param signature 方法签名属性字符串
     * @return 方法级类型参数名称列表
     */
    public static List<String> extractMethodTypeParams(String signature) {
        return extractTypeParams(signature); // 格式相同:以 <...> 开头
    }

    /** 内部名 → 简短显示名(去包名,去 java.lang. 前缀) */
    private static String simpleTypeName(String internalName) {
        String name = internalName.replace('/', '.');
        if (name.startsWith("java.lang.") && name.indexOf('.', 10) < 0) {
            name = name.substring(10);
        }
        int dot = name.lastIndexOf('.');
        // 保留完整包名由发射器的 import 机制简写;此处给 displayName 用简短形式
        return name;
    }

    /**
     * 将类签名解析为父类型与接口类型数组.
     *
     * <p>类签名格式:{@code [<类型参数>]父类[接口]*}.
     * 对于类,返回数组 {@code [父类, 接口1, 接口2, ...]};
     * 对于接口(无父类),返回数组 {@code [接口1, 接口2, ...]}.
     * 每个元素都是含类型参数的 {@link JavaType}(如 {@code Base<String>}),
     * 调用方据此重建 {@code extends Base<String>} / {@code implements List<Integer>}.</p>
     *
     * @param signature 类签名属性字符串(如 {@code <T:Ljava/lang/Object;>LBase<Ljava/lang/String;>;})
     * @return 父类型与接口类型数组,解析失败或无签名返回 {@code null}
     */
    public static JavaType[] parseClassSignature(String signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        try {
            int i = 0;
            // 跳过开头的类型参数声明(如 <T:Ljava/lang/Object;>)
            if (signature.charAt(0) == '<') {
                int depth = 1;
                i = 1;
                while (i < signature.length() && depth > 0) {
                    char c = signature.charAt(i);
                    if (c == '<') {
                        depth++;
                    } else if (c == '>') {
                        depth--;
                    }
                    i++;
                }
            }
            List<JavaType> types = new ArrayList<>();
            var ref = new java.util.concurrent.atomic.AtomicReference<JavaType>();
            while (i < signature.length()) {
                i = parseTypeToJavaType(signature, i, ref);
                if (ref.get() != null) {
                    types.add(ref.get());
                }
            }
            return types.isEmpty() ? null : types.toArray(new JavaType[0]);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将方法签名解析为参数类型数组和返回类型.
     * 例如 {@code (TT;TU;)V} → params=[typevar(T), typevar(U)], returnType=void.
     *
     * <p>类型变量产 {@code kind=TYPE_VARIABLE}(descriptor 仍为 "T名字;",
     * displayName 仍为裸变量名),与 parseClassSignature/parseGenericType
     * 一致,CLASS 伪装已彻底消除.</p>
     *
     * @param signature 方法签名属性字符串(如 {@code <T:Ljava/lang/Object;>(TT;TU;)V})
     * @return {@code [paramTypes..., returnType]},解析失败返回 {@code null}
     */
    public static JavaType[] parseMethodSignature(String signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        try {
            // 跳过开头的类型参数声明(如 <T:Ljava/lang/Object;>)
            int i = 0;
            if (signature.charAt(0) == '<') {
                int depth = 1;
                i = 1;
                while (i < signature.length() && depth > 0) {
                    char c = signature.charAt(i);
                    if (c == '<') {
                        depth++;
                    } else if (c == '>') {
                        depth--;
                    }
                    i++;
                }
            }
            if (i >= signature.length() || signature.charAt(i) != '(') {
                return null;
            }
            i++; // 跳过 '('
            List<JavaType> types = new ArrayList<>();
            var ref = new java.util.concurrent.atomic.AtomicReference<JavaType>();
            // 解析参数类型
            while (i < signature.length() && signature.charAt(i) != ')') {
                i = parseTypeToJavaType(signature, i, ref);
                types.add(ref.get());
            }
            i++; // 跳过 ')'
            // 解析返回类型
            if (i < signature.length()) {
                i = parseTypeToJavaType(signature, i, ref);
                types.add(ref.get());
            }
            return types.toArray(new JavaType[0]);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将字段或方法签名解析为带类型参数的 {@link JavaType} 结构体.
     *
     * <p>对于 {@code Ljava/util/List<Ljava/lang/String;>;} 等泛型类型,
     * 返回的 JavaType 将包含 typeArguments = [JavaType("java/lang/String")].
     *
     * @param sig 类型签名字符串
     * @return 包含类型参数的 JavaType,解析失败则返回 {@code null}
     */
    public static JavaType parseGenericType(String sig) {
        if (sig == null || sig.isEmpty()) {
            return null;
        }
        try {
            var result = new java.util.concurrent.atomic.AtomicReference<JavaType>();
            parseTypeToJavaType(sig, 0, result);
            return result.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将类型签名字符串解析为 JavaType 结构体.
     * 类型变量产 {@code kind=TYPE_VARIABLE}(descriptor="T名字;",
     * internalName=裸变量名),所有入口一致,无 CLASS 伪装.
     *
     * @param sig 完整签名字符串
     * @param i   当前解析起始位置
     * @param out 输出结果容器
     * @return 解析后的下一个位置
     */
    private static int parseTypeToJavaType(String sig, int i,
                                           java.util.concurrent.atomic.AtomicReference<JavaType> out) {
        if (i >= sig.length()) {
            return i;
        }
        char c = sig.charAt(i);
        switch (c) {
            // 基本类型
            case 'B':
                out.set(JavaType.BYTE);
                return i + 1;
            case 'C':
                out.set(JavaType.CHAR);
                return i + 1;
            case 'D':
                out.set(JavaType.DOUBLE);
                return i + 1;
            case 'F':
                out.set(JavaType.FLOAT);
                return i + 1;
            case 'I':
                out.set(JavaType.INT);
                return i + 1;
            case 'J':
                out.set(JavaType.LONG);
                return i + 1;
            case 'S':
                out.set(JavaType.SHORT);
                return i + 1;
            case 'Z':
                out.set(JavaType.BOOLEAN);
                return i + 1;
            case 'V':
                out.set(JavaType.VOID);
                return i + 1;
            // 类型变量(如 TT;)
            case 'T': {
                int semi = sig.indexOf(';', i);
                String tvName = sig.substring(i + 1, semi);
                // 类型变量:kind=TYPE_VARIABLE,descriptor 保持 "T<名字>;",
                // internalName 保持裸变量名(displayName 渲染为裸名).
                out.set(JavaType.typeVariable(tvName));
                return semi + 1;
            }
            // 引用类型(如 Ljava/lang/String; 或泛型 Lpkg/List<Lpkg/X;>;)
            case 'L': {
                int semi = sig.indexOf(';', i);
                String raw = sig.substring(i + 1, semi);
                int lt = raw.indexOf('<');
                if (lt >= 0) {
                    // 泛型类型:解析类名与类型参数.
                    // 注意:类型参数可能含通配符(Ljava/lang/Number;),
                    // 第一个分号属于参数而非类名——循环到配对的 '>' 为止.
                    String className = raw.substring(0, lt);
                    List<JavaType> typeArgs = new ArrayList<>();
                    int argPos = i + 1 + lt + 1;
                    while (argPos < sig.length() && sig.charAt(argPos) != '>') {
                        char ac = sig.charAt(argPos);
                        if (ac == '*') {
                            // 无界通配符 ?
                            typeArgs.add(new JavaType(TypeKind.WILDCARD, "?",
                                    "?", List.of(), 0));
                            argPos++;
                        } else if (ac == '+') {
                            // 上界通配符 ? extends T
                            var ref = new java.util.concurrent.atomic.AtomicReference<JavaType>();
                            argPos = parseTypeToJavaType(sig, argPos + 1, ref);
                            JavaType bound = ref.get();
                            String boundName = bound != null && bound.internalName() != null
                                    ? simpleTypeName(bound.internalName()) : "Object";
                            typeArgs.add(new JavaType(TypeKind.WILDCARD,
                                    "? extends " + boundName, "?",
                                    bound != null ? List.of(bound) : List.of(), 0));
                        } else if (ac == '-') {
                            // 下界通配符 ? super T
                            var ref = new java.util.concurrent.atomic.AtomicReference<JavaType>();
                            argPos = parseTypeToJavaType(sig, argPos + 1, ref);
                            JavaType bound = ref.get();
                            String boundName = bound != null && bound.internalName() != null
                                    ? simpleTypeName(bound.internalName()) : "Object";
                            typeArgs.add(new JavaType(TypeKind.WILDCARD,
                                    "? super " + boundName, "?",
                                    bound != null ? List.of(bound) : List.of(), 0));
                        } else {
                            var ref = new java.util.concurrent.atomic.AtomicReference<JavaType>();
                            argPos = parseTypeToJavaType(sig, argPos, ref);
                            if (ref.get() != null) {
                                typeArgs.add(ref.get());
                            }
                        }
                    }
                    // argPos 指向 '>',描述符结束于其后分号
                    int endSemi = sig.indexOf(';', argPos);
                    out.set(new JavaType(TypeKind.CLASS, className,
                            "L" + className + ";", typeArgs, 0));
                    return endSemi + 1;
                } else {
                    out.set(JavaType.classType(raw));
                }
                return semi + 1;
            }
            // 数组类型
            case '[': {
                var elemRef = new java.util.concurrent.atomic.AtomicReference<JavaType>();
                int next = parseTypeToJavaType(sig, i + 1, elemRef);
                JavaType elem = elemRef.get();
                if (elem != null) {
                    out.set(JavaType.array(elem, 1));
                }
                return next;
            }
            default:
                out.set(JavaType.classType("java/lang/Object"));
                return i + 1;
        }
    }

}
