package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.structuring.ExprCleanup;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 泛型方法返回类型推断(轻量反射方案,替代点对点硬编码).
 *
 * <p>对 {@code java.*}/{@code javax.*} 的静态/实例方法,用反射
 * ({@link Class#forName} + {@link Method#getGenericReturnType()})
 * 获取方法的泛型签名,把方法类型变量从调用点实参类型绑定,再替换进
 * 返回类型.例如:</p>
 * <ul>
 *   <li>{@code Map.of("a", 1)} → 签名 {@code <K,V> Map<K,V> of(K,V)},
 *       实参 [String, Integer] → {@code Map<String,Integer>}</li>
 *   <li>{@code List.of("a","b")} → 固定元数重载 {@code <E> List<E> of(E,E)},
 *       实参 [String, String] → {@code List<String>}</li>
 *   <li>{@code Collections.emptyList()} → 无实参,T 未绑定 → 回退原始类型
 *       (目标类型在重编译时推断)</li>
 * </ul>
 *
 * <p>参照 Procyon 的 Reifier/TypeManager 思路,但轻量:不引入完整签名物化,
 * 仅反射 + 结构绑定.结果缓存按 声明类:方法名:擦除参数描述符 键控.
 * 反射失败/非 JDK 类/返回类型变量未全绑定 → 回退原始擦除返回类型.</p>
 */
public final class GenericMethodResolver {

    /** 方法泛型签名缓存:键 = declaringClass:methodName:eraseDesc */
    private static final Map<String, MethodSig> CACHE = new HashMap<>();

    private GenericMethodResolver() {
    }

    /**
     * 从调用点实参推断泛型返回类型.
     *
     * @param declaringClass 方法声明类内部名(如 {@code java/util/Map})
     * @param methodName     方法名
     * @param returnType     描述符原始返回类型(推断失败时的回退)
     * @param erasedParamTypes 描述符擦除参数类型(用于反射定位重载)
     * @param args           调用点实参(可为 null)
     * @return 推断后的返回类型,无法推断时原样返回 {@code returnType}
     */
    public static JavaType inferGenericReturnType(String declaringClass,
                                                  String methodName,
                                                  JavaType returnType,
                                                  JavaType[] erasedParamTypes,
                                                  List<Value> args) {
        if (declaringClass == null || methodName == null || returnType == null) {
            return returnType;
        }
        // 仅 JDK 方法走反射;用户类签名在字节码不可得,保持原行为
        if (!declaringClass.startsWith("java/") && !declaringClass.startsWith("javax/")) {
            return returnType;
        }
        MethodSig sig = load(declaringClass, methodName, erasedParamTypes);
        if (sig == null || sig.returnType() == null) {
            return returnType;
        }
        // 从实参绑定类型变量
        Map<String, JavaType> bindings = new HashMap<>();
        List<Value> argList = args != null ? args : List.of();
        int n = Math.min(sig.paramTypes().length, argList.size());
        for (int i = 0; i < n; i++) {
            Value arg = argList.get(i);
            if (arg != null && arg.type() != null) {
                bind(sig.paramTypes()[i], arg.type(), bindings);
            }
        }
        // 返回类型变量必须全部绑定,否则回退(避免部分推断输出错误类型)
        if (!allVarsBound(sig.returnType(), bindings)) {
            return returnType;
        }
        return substitute(sig.returnType(), bindings);
    }

    /**
     * 反射获取方法的泛型参数类型(含类型变量,如 LinkedHashMap.put → [K, V]).
     * 供 IrBuilder 对 JDK 方法实参插入 (K)/(V) 强转——字节码签名擦除为 Object.
     */
    public static JavaType[] genericParamTypes(String declaringClass, String methodName,
                                               JavaType[] erasedParamTypes) {
        if (declaringClass == null || methodName == null) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName(declaringClass.replace('/', '.'));
            Class<?>[] erased = toErasureClasses(erasedParamTypes);
            Method m = clazz.getMethod(methodName, erased);
            Type[] genericParams = m.getGenericParameterTypes();
            JavaType[] result = new JavaType[genericParams.length];
            for (int i = 0; i < genericParams.length; i++) {
                Type t = genericParams[i];
                // 方法级类型变量(如 Function.apply 的 <T> T apply(T) 的 T)未绑定,
                // 强转 (T) 无意义且错误——用擦除类型(通常 Object)使其不触发强转.
                // 类级类型变量(如 LinkedHashMap.put 的 K/V)保留,供 (K)/(V) 强转.
                if (t instanceof TypeVariable<?> tv
                        && tv.getGenericDeclaration() instanceof Method) {
                    result[i] = erasedParamTypes != null && i < erasedParamTypes.length
                            ? erasedParamTypes[i] : JavaType.classType("java/lang/Object");
                } else {
                    result[i] = reify(t);
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 类是否带类型参数(泛型类).用于菱形推断:泛型类的 {@code new} 才能发射
     * {@code <>}.反射失败(非 JDK/不可加载)返回 {@code false}.
     */
    public static boolean isGenericClass(String declaringClass) {
        if (declaringClass == null) {
            return false;
        }
        try {
            Class<?> c = Class.forName(declaringClass.replace('/', '.'));
            return c.getTypeParameters().length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 是否存在参数个数匹配且涉及类型变量的构造器(有参菱形安全性).
     *
     * <p>{@code new Foo<>(args)} 的菱形推断要求构造器参数涉及类型变量;
     * 若泛型类的构造器参数不含类型变量(如 {@code Box<T> { Box(int size) }}),
     * 发射 {@code new Box<>(5)} 会报"无法推断类型实参".返回 {@code false}
     * 时不应为有参 {@code new} 置菱形标志.</p>
     *
     * @param declaringClass 类内部名
     * @param argCount       构造器实参数(按参数个数匹配重载,实参的具体类型
     *                       可能与声明的擦除参数不同,不能直接 getConstructor)
     */
    public static boolean ctorParamsBindTypeVars(String declaringClass, int argCount) {
        if (declaringClass == null) {
            return false;
        }
        try {
            Class<?> c = Class.forName(declaringClass.replace('/', '.'));
            for (java.lang.reflect.Constructor<?> ctor : c.getConstructors()) {
                if (ctor.getParameterTypes().length != argCount) {
                    continue;
                }
                for (Type t : ctor.getGenericParameterTypes()) {
                    if (containsTypeVariable(reify(t))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从显式方法泛型签名推断返回类型(供被反编译类自身方法使用).
     *
     * <p>反射路径只覆盖 {@code java.*}/{@code javax.*};被反编译的用户类
     * (如 {@code Cache<K,V>} 接口)的 {@code get} 方法字节码签名被擦除为
     * {@code Object},但其 Signature 属性 {@code (TK;)TV;} 完整可得——
     * 由调用方用 {@link SignatureParser#parseMethodSignature} 解析后传入.
     * 绑定分两步:</p>
     * <ol>
     *   <li>类类型参数按位置对齐接收者实参({@code this} 接收者类型为
     *       {@code Cache<K,V>} → K→K, V→V);</li>
     *   <li>方法类型参数从调用点实参绑定(与方法返回推断同逻辑).</li>
     * </ol>
     *
     * @param sigParamTypes 方法签名的参数类型(含类型变量)
     * @param sigReturnType 方法签名的返回类型(含类型变量)
     * @param classTypeParams 声明类的类型参数(如 K,V,以 TYPE_VARIABLE 表示)
     * @param receiverType   接收者的泛型类型(带类型实参;无实参时类绑定为空)
     * @param returnType     描述符原始返回类型(回退)
     * @param args           调用点实参(可为 null)
     * @return 推断后的返回类型,无法推断时原样返回 {@code returnType}
     */
    public static JavaType inferFromSignature(JavaType[] sigParamTypes,
                                              JavaType sigReturnType,
                                              JavaType[] classTypeParams,
                                              JavaType receiverType,
                                              JavaType returnType,
                                              List<Value> args) {
        if (sigParamTypes == null || sigReturnType == null || returnType == null) {
            return returnType;
        }
        Map<String, JavaType> bindings = new HashMap<>();
        // 类类型参数按位置对齐接收者实参(如 this 接收者 Cache<K,V>)
        if (receiverType != null && classTypeParams != null) {
            int n = Math.min(classTypeParams.length, receiverType.typeArguments().size());
            for (int i = 0; i < n; i++) {
                JavaType clsParam = classTypeParams[i];
                if (clsParam != null && clsParam.internalName() != null) {
                    bindings.put(clsParam.internalName(), receiverType.typeArguments().get(i));
                }
            }
        }
        // 方法类型变量从实参绑定
        List<Value> argList = args != null ? args : List.of();
        int na = Math.min(sigParamTypes.length, argList.size());
        for (int i = 0; i < na; i++) {
            Value arg = argList.get(i);
            if (arg != null && arg.type() != null) {
                bind(sigParamTypes[i], arg.type(), bindings);
            }
        }
        if (!allVarsBound(sigReturnType, bindings)) {
            return returnType;
        }
        return substitute(sigReturnType, bindings);
    }

    /** 类型树中是否含类型变量(TYPE_VARIABLE 或泛型实参/数组元素/通配符边界内). */
    public static boolean containsTypeVariable(JavaType t) {
        if (t == null) {
            return false;
        }
        return switch (t.kind()) {
            case TYPE_VARIABLE -> true;
            case CLASS -> t.typeArguments().stream().anyMatch(
                    GenericMethodResolver::containsTypeVariable);
            case ARRAY -> containsTypeVariable(JavaType.elementOf(t));
            case WILDCARD -> !t.typeArguments().isEmpty()
                    && containsTypeVariable(t.typeArguments().getFirst());
            default -> false;
        };
    }

    /**
     * 从接收者类型实参推断实例方法返回类型.
     *
     * <p>实例方法返回类型常使用类的类型变量(如 {@code List<E>.get(int) → E},
     * {@code Map<K,V>.get(K) → V});接收者携带类型实参时
     * (如 {@code List<String> l} 的 {@code l.get(0)}),把类类型参数按位置
     * 对齐到接收者实参再替换进返回类型 → {@code String}. 这是冗余强转
     * 抑制的前提:({@code String}) l.get(0) 的强转在操作数类型已为 String 时
     * 可移除.</p>
     *
     * @param declaringClass 方法声明类内部名
     * @param methodName     方法名
     * @param receiverType   接收者的泛型类型(带类型实参;无实参时本方法直接回退)
     * @param returnType     描述符原始返回类型(回退)
     * @param erasedParamTypes 擦除参数类型(反射定位重载)
     * @param args           调用点实参
     * @return 推断后的返回类型,无法推断时原样返回 {@code returnType}
     */
    public static JavaType inferInstanceReturnType(String declaringClass,
                                                   String methodName,
                                                   JavaType receiverType,
                                                   JavaType returnType,
                                                   JavaType[] erasedParamTypes,
                                                   List<Value> args) {
        if (declaringClass == null || methodName == null || returnType == null
                || receiverType == null || receiverType.typeArguments().isEmpty()) {
            return returnType;
        }
        if (!declaringClass.startsWith("java/") && !declaringClass.startsWith("javax/")) {
            return returnType;
        }
        MethodSig sig = load(declaringClass, methodName, erasedParamTypes);
        if (sig == null || sig.returnType() == null) {
            return returnType;
        }
        Map<String, JavaType> bindings = mapClassTypeParams(declaringClass, receiverType);
        // 方法自身类型变量从实参绑定(与方法返回推断同逻辑)
        List<Value> argList = args != null ? args : List.of();
        int n = Math.min(sig.paramTypes().length, argList.size());
        for (int i = 0; i < n; i++) {
            Value arg = argList.get(i);
            if (arg != null && arg.type() != null) {
                bind(sig.paramTypes()[i], arg.type(), bindings);
            }
        }
        if (!allVarsBound(sig.returnType(), bindings)) {
            return returnType;
        }
        return substitute(sig.returnType(), bindings);
    }

    /**
     * 把类的类型参数按位置对齐到接收者的类型实参.
     * 如 {@code Map<String,Integer>} 接收者 → K→String, V→Integer.
     */
    private static Map<String, JavaType> mapClassTypeParams(String declaringClass,
                                                            JavaType receiverType) {
        Map<String, JavaType> bindings = new HashMap<>();
        try {
            Class<?> c = Class.forName(declaringClass.replace('/', '.'));
            TypeVariable<?>[] tps = c.getTypeParameters();
            int n = Math.min(tps.length, receiverType.typeArguments().size());
            for (int i = 0; i < n; i++) {
                bindings.put(tps[i].getName(), receiverType.typeArguments().get(i));
            }
        } catch (Exception e) {
            // 反射失败:保持空绑定
        }
        return bindings;
    }

    /**
     * 把方法泛型参数绑定到接收者类型实参,供参数级泛型强转使用.
     *
     * <p>JDK 方法签名中的类型变量(Predicate.test 的 T)在调用类不在作用域,
     * 直接作为泛型参数会插入 {@code (T) x} 强转到未定义类型变量(编译错误).
     * 当接收者携带泛型实参(如 {@code param0 : Predicate<? super E>})时,
     * 按位置把类类型参数绑定为接收者实参 → {@code test} 形参变为 {@code ? super E},
     * 强转目标经 {@link ExprCleanup#wildcardBound} 得 {@code E}({@code (E) x}).</p>
     *
     * <p>两个来源的类类型参数:</p>
     * <ul>
     *   <li>{@code classTypeParams != null}(自类,类签名可得):按位置恒等绑定,
     *       保留在作用域的类型变量(如 Cache&lt;K,V&gt; 的 get 形参 K).</li>
     *   <li>{@code classTypeParams == null}(跨类,JDK):反射类类型参数;反射失败
     *       或接收者无类型实参(原始类型)无法绑定 → 返回 null,调用方不设泛型
     *       参数(避免 {@code (T)});绑定后仍残留未绑定类型变量(方法级)同样返回 null.</li>
     * </ul>
     */
    public static JavaType[] bindParamsToReceiver(JavaType[] methodParams,
                                                  JavaType[] classTypeParams,
                                                  String declaringClass,
                                                  JavaType receiverType) {
        if (methodParams == null || methodParams.length == 0) {
            return methodParams;
        }
        if (classTypeParams != null && classTypeParams.length > 0) {
            // 自类:类签名类型参数按位置绑定(恒等映射,保留在作用域类型变量)
            if (receiverType == null || receiverType.typeArguments().isEmpty()) {
                return methodParams;
            }
            Map<String, JavaType> bindings = new HashMap<>();
            int n = Math.min(classTypeParams.length, receiverType.typeArguments().size());
            for (int i = 0; i < n; i++) {
                if (classTypeParams[i] != null && classTypeParams[i].internalName() != null) {
                    bindings.put(classTypeParams[i].internalName(),
                            receiverType.typeArguments().get(i));
                }
            }
            return bindings.isEmpty() ? methodParams : substituteAll(methodParams, bindings);
        }
        // 跨类:反射类类型参数;失败/原始接收者无法绑定 → null
        if (receiverType == null || receiverType.typeArguments().isEmpty()) {
            return null;
        }
        Map<String, JavaType> bindings = mapClassTypeParams(declaringClass, receiverType);
        if (bindings.isEmpty()) {
            return null;
        }
        JavaType[] out = substituteAll(methodParams, bindings);
        // 绑定后仍含未绑定类型变量(方法级 T 等,不在接收者实参)→ 保守不设
        Set<String> recvVars = new HashSet<>();
        for (JavaType arg : receiverType.typeArguments()) {
            collectVarNames(arg, recvVars);
        }
        for (JavaType p : out) {
            if (containsForeignVar(p, recvVars)) {
                return null;
            }
        }
        return out;
    }

    private static JavaType[] substituteAll(JavaType[] params, Map<String, JavaType> bindings) {
        JavaType[] out = new JavaType[params.length];
        for (int i = 0; i < params.length; i++) {
            out[i] = substitute(params[i], bindings);
        }
        return out;
    }

    /** 类型树中是否存在"不在接收者类型实参变量集合中"的类型变量(未绑定方法级变量). */
    private static boolean containsForeignVar(JavaType t, Set<String> recvVars) {
        if (t == null) {
            return false;
        }
        return switch (t.kind()) {
            case TYPE_VARIABLE -> t.internalName() != null && !recvVars.contains(t.internalName());
            case CLASS -> t.typeArguments().stream().anyMatch(a -> containsForeignVar(a, recvVars));
            case ARRAY -> containsForeignVar(JavaType.elementOf(t), recvVars);
            case WILDCARD -> !t.typeArguments().isEmpty()
                    && containsForeignVar(t.typeArguments().getFirst(), recvVars);
            default -> false;
        };
    }

    /** 收集类型树中的类型变量名. */
    private static void collectVarNames(JavaType t, Set<String> out) {
        if (t == null) {
            return;
        }
        switch (t.kind()) {
            case TYPE_VARIABLE -> {
                if (t.internalName() != null) {
                    out.add(t.internalName());
                }
            }
            case CLASS -> t.typeArguments().forEach(a -> collectVarNames(a, out));
            case ARRAY -> collectVarNames(JavaType.elementOf(t), out);
            case WILDCARD -> {
                if (!t.typeArguments().isEmpty()) {
                    collectVarNames(t.typeArguments().getFirst(), out);
                }
            }
            default -> {
            }
        }
    }

    /** 反射加载方法签名(带缓存). */
    private static MethodSig load(String declaringClass, String methodName,
                                  JavaType[] erasedParamTypes) {
        String key = declaringClass + ":" + methodName + ":" + eraseDesc(erasedParamTypes);
        MethodSig cached = CACHE.get(key);
        if (cached != null || CACHE.containsKey(key)) {
            return cached;
        }
        try {
            Class<?> clazz = Class.forName(declaringClass.replace('/', '.'));
            Class<?>[] erased = toErasureClasses(erasedParamTypes);
            Method m = clazz.getMethod(methodName, erased);
            Type[] genericParams = m.getGenericParameterTypes();
            JavaType[] paramTypes = new JavaType[genericParams.length];
            for (int i = 0; i < genericParams.length; i++) {
                paramTypes[i] = reify(genericParams[i]);
            }
            MethodSig sig = new MethodSig(paramTypes, reify(m.getGenericReturnType()));
            CACHE.put(key, sig);
            return sig;
        } catch (Exception e) {
            CACHE.put(key, null);
            return null;
        }
    }

    /** 擦除参数类型的描述符(缓存键用). */
    private static String eraseDesc(JavaType[] types) {
        if (types == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JavaType t : types) {
            sb.append(t.descriptor() != null ? t.descriptor() : "?");
        }
        return sb.toString();
    }

    /** JavaType → Class(反射定位重载的擦除参数). */
    private static Class<?>[] toErasureClasses(JavaType[] types) {
        if (types == null) {
            return new Class<?>[0];
        }
        Class<?>[] result = new Class<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            result[i] = toErasureClass(types[i]);
        }
        return result;
    }

    private static Class<?> toErasureClass(JavaType t) {
        if (t == null) {
            return Object.class;
        }
        return switch (t.kind()) {
            case VOID -> void.class;
            case BOOLEAN -> boolean.class;
            case BYTE -> byte.class;
            case SHORT -> short.class;
            case CHAR -> char.class;
            case INT -> int.class;
            case LONG -> long.class;
            case FLOAT -> float.class;
            case DOUBLE -> double.class;
            case ARRAY -> {
                // 数组:用描述符反查(JVM 数组类),如 [Ljava.lang.Object;
                if (t.descriptor() != null) {
                    try {
                        yield Class.forName(t.descriptor().replace('/', '.'));
                    } catch (ClassNotFoundException e) {
                        yield Object.class;
                    }
                }
                yield Object.class;
            }
            default -> {
                if (t.internalName() != null) {
                    try {
                        yield Class.forName(t.internalName().replace('/', '.'));
                    } catch (ClassNotFoundException e) {
                        yield Object.class;
                    }
                }
                yield Object.class;
            }
        };
    }

    /**
     * 把 {@code java.lang.reflect.Type} 物化为 BDEC JavaType(轻量 Reifier).
     * 支持 TypeVariable / ParameterizedType / Class / GenericArrayType / WildcardType.
     */
    private static JavaType reify(Type t) {
        if (t instanceof TypeVariable<?> tv) {
            return JavaType.typeVariable(tv.getName());
        }
        if (t instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rc) {
                String internal = rc.getName().replace('.', '/');
                List<JavaType> args = new ArrayList<>();
                for (Type at : pt.getActualTypeArguments()) {
                    args.add(reify(at));
                }
                return new JavaType(TypeKind.CLASS, internal, "L" + internal + ";",
                        args, 0);
            }
        }
        if (t instanceof Class<?> c) {
            if (c.isPrimitive()) {
                return primitiveOf(c);
            }
            if (c.isArray()) {
                // 类形式数组(如 String[].class):按组件递归
                return JavaType.array(reify(c.getComponentType()), 1);
            }
            String internal = c.getName().replace('.', '/');
            return JavaType.classType(internal);
        }
        if (t instanceof GenericArrayType gat) {
            return JavaType.array(reify(gat.getGenericComponentType()), 1);
        }
        if (t instanceof WildcardType wt) {
            Type[] lower = wt.getLowerBounds();
            Type[] upper = wt.getUpperBounds();
            if (lower.length > 0) {
                JavaType bound = reify(lower[0]);
                return new JavaType(TypeKind.WILDCARD, "? super ",
                        "?", List.of(bound), 0);
            }
            if (upper.length > 0 && upper[0] != Object.class) {
                JavaType bound = reify(upper[0]);
                return new JavaType(TypeKind.WILDCARD, "? extends ",
                        "?", List.of(bound), 0);
            }
            return new JavaType(TypeKind.WILDCARD, "?", "?", List.of(), 0);
        }
        return JavaType.classType("java/lang/Object");
    }

    private static JavaType primitiveOf(Class<?> c) {
        if (c == int.class) {
            return JavaType.INT;
        }
        if (c == boolean.class) {
            return JavaType.BOOLEAN;
        }
        if (c == byte.class) {
            return JavaType.BYTE;
        }
        if (c == short.class) {
            return JavaType.SHORT;
        }
        if (c == char.class) {
            return JavaType.CHAR;
        }
        if (c == long.class) {
            return JavaType.LONG;
        }
        if (c == float.class) {
            return JavaType.FLOAT;
        }
        if (c == double.class) {
            return JavaType.DOUBLE;
        }
        if (c == void.class) {
            return JavaType.VOID;
        }
        return JavaType.classType("java/lang/Object");
    }

    /** 结构绑定:签名参数类型 ↔ 实参类型,收集类型变量绑定. */
    private static void bind(JavaType sigParam, JavaType actual,
                             Map<String, JavaType> bindings) {
        if (sigParam == null || actual == null) {
            return;
        }
        switch (sigParam.kind()) {
            case TYPE_VARIABLE -> {
                String name = sigParam.internalName();
                if (name != null && !bindings.containsKey(name)) {
                    bindings.put(name, actual);
                }
            }
            case ARRAY -> {
                if (actual.kind() == TypeKind.ARRAY) {
                    bind(JavaType.elementOf(sigParam), JavaType.elementOf(actual), bindings);
                }
            }
            case CLASS -> {
                if (actual.kind() == TypeKind.CLASS) {
                    int n = Math.min(sigParam.typeArguments().size(),
                            actual.typeArguments().size());
                    for (int i = 0; i < n; i++) {
                        bind(sigParam.typeArguments().get(i),
                                actual.typeArguments().get(i), bindings);
                    }
                }
            }
            case WILDCARD -> {
                if (!sigParam.typeArguments().isEmpty()) {
                    bind(sigParam.typeArguments().getFirst(), actual, bindings);
                }
            }
            default -> {
            }
        }
    }

    /** 返回类型中所有类型变量是否已绑定(未绑定则回退原始类型). */
    private static boolean allVarsBound(JavaType t, Map<String, JavaType> bindings) {
        if (t == null) {
            return true;
        }
        return switch (t.kind()) {
            case TYPE_VARIABLE -> {
                String name = t.internalName();
                yield name != null && bindings.containsKey(name);
            }
            case CLASS -> {
                boolean all = true;
                for (JavaType arg : t.typeArguments()) {
                    if (!allVarsBound(arg, bindings)) {
                        all = false;
                        break;
                    }
                }
                yield all;
            }
            case ARRAY -> allVarsBound(JavaType.elementOf(t), bindings);
            case WILDCARD -> {
                if (t.typeArguments().isEmpty()) {
                    yield true;
                }
                yield allVarsBound(t.typeArguments().getFirst(), bindings);
            }
            default -> true;
        };
    }

    /** 把类型变量绑定替换进返回类型. */
    private static JavaType substitute(JavaType t, Map<String, JavaType> bindings) {
        if (t == null) {
            return null;
        }
        return switch (t.kind()) {
            case TYPE_VARIABLE -> {
                String name = t.internalName();
                JavaType bound = name != null ? bindings.get(name) : null;
                yield bound != null ? bound : t;
            }
            case CLASS -> {
                if (t.typeArguments().isEmpty()) {
                    yield t;
                }
                List<JavaType> newArgs = new ArrayList<>(t.typeArguments().size());
                for (JavaType arg : t.typeArguments()) {
                    newArgs.add(substitute(arg, bindings));
                }
                yield new JavaType(TypeKind.CLASS, t.internalName(),
                        t.descriptor(), newArgs, t.arrayDimensions());
            }
            case ARRAY -> JavaType.array(substitute(JavaType.elementOf(t), bindings),
                    t.arrayDimensions());
            case WILDCARD -> {
                if (t.typeArguments().isEmpty()) {
                    yield t;
                }
                yield new JavaType(TypeKind.WILDCARD, t.internalName(), t.descriptor(),
                        List.of(substitute(t.typeArguments().getFirst(), bindings)), 0);
            }
            default -> t;
        };
    }

    /** 方法签名模板:参数类型与返回类型(含类型变量). */
    private record MethodSig(JavaType[] paramTypes, JavaType returnType) {
    }
}
