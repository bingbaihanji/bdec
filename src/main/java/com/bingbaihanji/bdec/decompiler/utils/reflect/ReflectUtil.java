package com.bingbaihanji.bdec.decompiler.utils.reflect;


import com.bingbaihanji.bdec.decompiler.utils.collection.ArrayMap;
import sun.reflect.ReflectionFactory;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反射工具类
 *
 * @author Matt Coley
 * @author xDark
 */
public final class ReflectUtil {

    private static final Map<Class<?>, ThrowableGetter<?>> GETTERS = new ArrayMap<>();

    private static final Map<Class<?>, ThrowableSetter<?>> SETTERS = new ArrayMap<>();

    private static final ThrowableGetter<?> DEFAULT_GETTER = ReflectUtil::get;

    private static final ThrowableSetter<?> DEFAULT_SETTER = ReflectUtil::set;

//    private final static Logger log = LoggerFactory.getLogger(ReflectUtil.class);

    /** 嵌套属性路径分隔符 */
    private static final String PATH_SEPARATOR = "\\.";

    private static MethodHandles.Lookup trustedLookup;

    static {
        // 读取器
        GETTERS.put(boolean.class, (ThrowableGetter<Boolean>) Field::getBoolean);
        GETTERS.put(byte.class, (ThrowableGetter<Byte>) Field::getByte);
        GETTERS.put(char.class, (ThrowableGetter<Character>) Field::getChar);
        GETTERS.put(short.class, (ThrowableGetter<Short>) Field::getShort);
        GETTERS.put(int.class, (ThrowableGetter<Integer>) Field::getInt);
        GETTERS.put(long.class, (ThrowableGetter<Long>) Field::getLong);
        GETTERS.put(float.class, (ThrowableGetter<Float>) Field::getFloat);
        GETTERS.put(double.class, (ThrowableGetter<Double>) Field::getDouble);

        // 写入器
        SETTERS.put(boolean.class, (ThrowableSetter<Boolean>) Field::setBoolean);
        SETTERS.put(byte.class, (ThrowableSetter<Byte>) Field::setByte);
        SETTERS.put(char.class, (ThrowableSetter<Character>) Field::setChar);
        SETTERS.put(short.class, (ThrowableSetter<Short>) Field::setShort);
        SETTERS.put(int.class, (ThrowableSetter<Integer>) Field::setInt);
        SETTERS.put(long.class, (ThrowableSetter<Long>) Field::setLong);
        SETTERS.put(float.class, (ThrowableSetter<Float>) Field::setFloat);
        SETTERS.put(double.class, (ThrowableSetter<Double>) Field::setDouble);

        // 绕过访问限制
        AccessPatcher.patch();
    }

    /**
     * 禁止任何实例化
     */
    private ReflectUtil() {
        throw new AssertionError("工具类不允许实例化");
    }

    // == 实例化 ==

    /**
     * 初始化反射访问修补
     */
    public static void patch() {
        // 有意留空 修补在静态初始化块中完成,调用方通过调用此方法
        // 即可触发该初始化
    }

    /**
     * 使用默认无参构造器创建实例
     * @param cls 目标类
     * @param <T> 实例类型
     * @return 新创建的实例
     * @throws ReflectionRuntimeException 如果类没有无参构造器或实例化失败
     */
    public static <T> T newInstance(Class<T> cls) {
        try {
            Constructor<T> ctor = cls.getDeclaredConstructor();
            makeAccessible(ctor);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new ReflectionRuntimeException(cls.getName() + " 缺少无参构造器", e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ReflectionRuntimeException(cls.getName() + " 实例化失败", e);
        }
    }

    /**
     * 根据类名创建实例
     * @param className 完全限定类名
     * @return 新创建的实例
     * @throws ReflectionRuntimeException 如果类不存在或实例化失败
     */
    public static Object newInstance(String className) {
        try {
            return newInstance(Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new ReflectionRuntimeException("类不存在: " + className, e);
        }
    }

    // == 字段访问 ==

    /**
     * 使用指定构造器和参数创建实例
     * @param ctor 构造器对象
     * @param args 构造器参数
     * @return 新创建的实例
     * @throws ReflectionRuntimeException 如果实例化失败
     */
    public static Object newInstance(Constructor<?> ctor, Object... args) {
        try {
            makeAccessible(ctor);
            return ctor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ReflectionRuntimeException("构造器实例化失败: " + ctor, e);
        }
    }

    /**
     * 沿继承链向上查找字段(包含私有字段),并设为可访问
     * @param cls 目标类
     * @param name 字段名
     * @return 字段对象,找不到则返回 null
     */
    public static Field getDeclaredField(Class<?> cls, String name) {
        for (Class<?> search = cls; search != null && search != Object.class; search = search.getSuperclass()) {
            try {
                Field field = search.getDeclaredField(name);
                makeAccessible(field);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            }
        }
        return null;
    }

    /**
     * 获取对象指定字段的值,字段不存在时抛出异常
     * @param target 目标对象
     * @param fieldName 字段名
     * @return 字段值
     * @throws ReflectionRuntimeException 如果字段不存在或读取失败
     */
    public static Object getFieldValue(Object target, String fieldName) {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null");
        }
        Field f = getDeclaredField(target.getClass(), fieldName);
        if (f == null) {
            throw new ReflectionRuntimeException("字段不存在: " + fieldName + " on " + target.getClass().getName());
        }
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new ReflectionRuntimeException("读取字段失败: " + fieldName, e);
        }
    }

    /**
     * 获取对象指定字段的值并转换为期望类型
     * @param target 目标对象
     * @param fieldName 字段名
     * @param expectedType 期望的返回类型
     * @param <T> 类型参数
     * @return 字段值
     * @throws ReflectionRuntimeException 如果字段不存在或读取失败
     */
    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Object target, String fieldName, Class<T> expectedType) {
        return (T) getFieldValue(target, fieldName);
    }

    /**
     * 设置对象指定字段的值,字段不存在时抛出异常
     * @param target 目标对象
     * @param fieldName 字段名
     * @param value 要设置的值
     * @throws ReflectionRuntimeException 如果字段不存在或设置失败
     */
    public static void setFieldValue(Object target, String fieldName, Object value) {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null");
        }
        Field field = getDeclaredField(target.getClass(), fieldName);
        if (field == null) {
            throw new ReflectionRuntimeException("字段不存在: " + fieldName + " on " + target.getClass().getName());
        }
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new ReflectionRuntimeException("写入字段失败: " + fieldName, e);
        }
    }

    // == 方法查找与调用 ==

    /**
     * 获取类的所有字段(包括继承的字段,不含 Object 类字段)
     * @param cls 目标类
     * @return 字段列表,cls 为 null 时返回空列表
     */
    public static List<Field> getAllFields(Class<?> cls) {
        if (cls == null) {
            return Collections.emptyList();
        }
        List<Field> list = new ArrayList<>();
        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                makeAccessible(f);
                list.add(f);
            }
        }
        return list;
    }

    /**
     * 沿继承链精确查找方法(按方法名 + 参数类型匹配),包含接口默认方法
     * @param cls 目标类
     * @param methodName 方法名
     * @param paramTypes 参数类型数组
     * @return 方法对象,找不到则返回 null
     */
    public static Method findMethod(Class<?> cls, String methodName, Class<?>... paramTypes) {
        for (Class<?> search = cls; search != null && search != Object.class; search = search.getSuperclass()) {
            try {
                Method m = search.getDeclaredMethod(methodName, paramTypes);
                makeAccessible(m);
                return m;
            } catch (NoSuchMethodException ignored) {
                // 继续向父类查找
            }
        }
        if (cls != null) {
            for (Class<?> iface : cls.getInterfaces()) {
                try {
                    Method m = iface.getDeclaredMethod(methodName, paramTypes);
                    makeAccessible(m);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // 继续下一个接口
                }
            }
        }
        return null;
    }

    /**
     * 根据方法名和参数个数查找方法(不精确匹配参数类型),包含接口默认方法
     * @param cls 目标类
     * @param methodName 方法名
     * @param argsNum 参数个数
     * @return 方法对象,找不到则返回 null
     */
    public static Method findMethodByName(Class<?> cls, String methodName, int argsNum) {
        if (cls == null) {
            return null;
        }
        for (Class<?> search = cls; search != null && search != Object.class; search = search.getSuperclass()) {
            for (Method m : search.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == argsNum) {
                    makeAccessible(m);
                    return m;
                }
            }
        }
        for (Class<?> iface : cls.getInterfaces()) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == argsNum) {
                    makeAccessible(m);
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 调用实例方法(自动匹配最合适的重载版本)
     * @param target 目标对象
     * @param methodName 方法名
     * @param args 方法参数
     * @return 方法返回值
     * @throws ReflectionRuntimeException 如果方法不存在或调用失败
     */
    public static Object invokeMethod(Object target, String methodName, Object... args) {
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null");
        }
        Method m = findBestMatchingMethod(target.getClass(), methodName, args);
        if (m == null) {
            throw new ReflectionRuntimeException("方法不存在: " + methodName + " on " + target.getClass().getName());
        }
        try {
            return m.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ReflectionRuntimeException("调用方法失败: " + methodName, e);
        }
    }

    /**
     * 调用静态方法(自动匹配最合适的重载版本)
     * @param cls 目标类
     * @param methodName 方法名
     * @param args 方法参数
     * @return 方法返回值
     * @throws ReflectionRuntimeException 如果方法不存在或调用失败
     */
    public static Object invokeStaticMethod(Class<?> cls, String methodName, Object... args) {
        Method m = findBestMatchingMethod(cls, methodName, args);
        if (m == null) {
            throw new ReflectionRuntimeException("静态方法不存在: " + methodName + " on " + cls.getName());
        }
        try {
            return m.invoke(null, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new ReflectionRuntimeException("调用静态方法失败: " + methodName, e);
        }
    }

    // == 嵌套属性访问 ==

    /**
     * 根据方法名和参数个数调用方法,自动进行参数类型转换
     * <p>
     * 支持常用类型自动转换:String,Integer,Long,Double,Float,Boolean 适用于参数类型不明确或由外部传入的场景
     * </p>
     * @param obj 目标对象
     * @param methodName 方法名
     * @param args 参数值数组
     * @param <E> 返回值类型
     * @return 方法执行结果,方法不存在时返回 null
     * @throws ReflectionRuntimeException 如果调用过程中出现异常
     */
    @SuppressWarnings("unchecked")
    public static <E> E invokeMethodByName(Object obj, String methodName, Object[] args) {
        Method method = findMethodByName(obj.getClass(), methodName, args.length);
        if (method == null) {
//            log.debug("在 [{}] 中,没有找到 [{}] 方法", obj.getClass(), methodName);
            return null;
        }
        try {
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] actualArgs = args.clone();
            for (int i = 0; i < paramTypes.length; i++) {
                if (actualArgs[i] != null && !actualArgs[i].getClass().equals(paramTypes[i])) {
                    actualArgs[i] = convertArg(actualArgs[i], paramTypes[i]);
                }
            }
            return (E) method.invoke(obj, actualArgs);
        } catch (Exception e) {
            throw new ReflectionRuntimeException("方法: " + method + ", 对象: " + obj, e);
        }
    }

    /**
     * 递归调用 getter,支持嵌套属性路径(如 "user.dept.name")
     * @param obj 目标对象
     * @param propertyName 属性路径,用点号分隔
     * @param <E> 返回值类型
     * @return 最内层属性值
     * @throws ReflectionRuntimeException 如果 getter 不存在或调用失败
     */
    @SuppressWarnings("unchecked")
    public static <E> E invokeGetter(Object obj, String propertyName) {
        Object object = obj;
        for (String name : propertyName.split(PATH_SEPARATOR)) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("属性路径包含空段: " + propertyName);
            }
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Method m = findMethod(object.getClass(), getter);
            if (m == null) {
                throw new ReflectionRuntimeException("Getter 不存在: " + getter + " on " + object.getClass().getName());
            }
            try {
                object = m.invoke(object);
                if (object == null) {
                    return null;
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ReflectionRuntimeException("调用 Getter 失败: " + getter, e);
            }
        }
        return (E) object;
    }

    /**
     * 递归调用 setter,支持嵌套属性路径 路径中前几层通过 getter 获取中间对象,最后一层执行 setter 赋值
     * @param obj 目标对象
     * @param propertyName 属性路径,用点号分隔
     * @param value 要设置的值
     * @param <E> 值的类型
     */
    public static <E> void invokeSetter(Object obj, String propertyName, E value) {
        Object object = obj;
        String[] names = propertyName.split(PATH_SEPARATOR);
        for (int i = 0; i < names.length; i++) {
            if (names[i].isEmpty()) {
                throw new IllegalArgumentException("属性路径包含空段: " + propertyName);
            }
            if (i < names.length - 1) {
                object = invokeGetter(object, names[i]);
            } else {
                String setter = "set" + Character.toUpperCase(names[i].charAt(0)) + names[i].substring(1);
                invokeMethodByName(object, setter, new Object[]{value});
            }
        }
    }

    // == 泛型类型 ==

    /**
     * 通过字段直接访问嵌套属性路径(如 "user.address.city")
     * @param target 目标对象
     * @param path 属性路径,用点号分隔
     * @return 属性值,路径中任意对象为 null 时返回 null
     */
    public static Object getProperty(Object target, String path) {
        if (target == null || path == null || path.isEmpty()) {
            return target;
        }
        Object cur = target;
        for (String p : path.split(PATH_SEPARATOR)) {
            if (cur == null) {
                return null;
            }
            cur = getFieldValue(cur, p);
        }
        return cur;
    }

    /**
     * 获取父类指定位置的实际泛型类型参数
     * @param cls 目标类
     * @param index 泛型参数索引(从 0 开始)
     * @return 泛型参数的实际类型,无法解析时返回 Object.class
     */
    public static Class<?> getSuperclassGenericType(Class<?> cls, int index) {
        Type t = cls.getGenericSuperclass();
        if (!(t instanceof ParameterizedType pt)) {
            return Object.class;
        }
        Type[] args = pt.getActualTypeArguments();
        if (index < 0 || index >= args.length) {
            return Object.class;
        }
        return resolveGenericArg(args, index);
    }

    /**
     * 获取父类的第一个实际泛型类型参数
     * @param cls 目标类
     * @param <T> 泛型参数类型
     * @return 第一个泛型实际类型,无法解析时返回 Object.class
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> getSuperclassGenericType(Class<?> cls) {
        return (Class<T>) getSuperclassGenericType(cls, 0);
    }

    // == 注解 ==

    /**
     * 获取字段指定位置的泛型参数类型(如 {@code List<String> → String.class})
     * @param f 字段对象
     * @param index 泛型参数索引(从 0 开始)
     * @return 泛型参数的实际类型,无法解析时返回 Object.class
     */
    public static Class<?> getFieldGenericType(Field f, int index) {
        Type t = f.getGenericType();
        if (!(t instanceof ParameterizedType pt)) {
            return Object.class;
        }
        Type[] args = pt.getActualTypeArguments();
        if (index < 0 || index >= args.length) {
            return Object.class;
        }
        return resolveGenericArg(args, index);
    }

    /**
     * 获取类上的指定注解
     * @param cls 目标类
     * @param annotationClass 注解类型
     * @param <A> 注解类型参数
     * @return 注解实例,不存在则返回 null
     */
    public static <A extends Annotation> A getAnnotation(Class<?> cls, Class<A> annotationClass) {
        return cls.getAnnotation(annotationClass);
    }

    /**
     * 获取字段上的指定注解
     * @param f 字段对象
     * @param annotationClass 注解类型
     * @param <A> 注解类型参数
     * @return 注解实例,不存在则返回 null
     */
    public static <A extends Annotation> A getAnnotation(Field f, Class<A> annotationClass) {
        return f.getAnnotation(annotationClass);
    }

    /**
     * 获取方法上的指定注解
     * @param m 方法对象
     * @param annotationClass 注解类型
     * @param <A> 注解类型参数
     * @return 注解实例,不存在则返回 null
     */
    public static <A extends Annotation> A getAnnotation(Method m, Class<A> annotationClass) {
        return m.getAnnotation(annotationClass);
    }

    // == 属性复制 ==

    /**
     * 检查元素是否标注了指定注解
     * @param element 被注解的元素(类,方法,字段等)
     * @param annotationClass 注解类型
     * @return 如果存在该注解返回 true
     */
    public static boolean hasAnnotation(AnnotatedElement element, Class<? extends Annotation> annotationClass) {
        return element.getAnnotation(annotationClass) != null;
    }

    // == 调试辅助 ==

    /**
     * 按字段名匹配,将源对象字段值复制到目标对象仅复制类型兼容的字段
     * @param src 源对象
     * @param dest 目标对象
     * @param ignoreNulls 是否跳过 null 值字段
     * @throws ReflectionRuntimeException 如果复制过程中发生异常
     */
    public static void copyFields(Object src, Object dest, boolean ignoreNulls) {
        if (src == null || dest == null) {
            throw new IllegalArgumentException("src/dest 不能为 null");
        }
        Class<?> destClass = dest.getClass();
        for (Field sf : getAllFields(src.getClass())) {
            try {
                Object val = sf.get(src);
                if (ignoreNulls && val == null) {
                    continue;
                }
                Field df = getDeclaredField(destClass, sf.getName());
                if (df == null || !df.getType().isAssignableFrom(sf.getType())) {
                    continue;
                }
                df.set(dest, val);
            } catch (IllegalAccessException e) {
                throw new ReflectionRuntimeException("复制字段失败: " + sf.getName(), e);
            }
        }
    }

    /**
     * 将对象的所有字段转为 Map(key 为字段名,value 为字段值)
     * @param obj 目标对象
     * @return 字段名到字段值的映射,obj 为 null 时返回空 Map
     */
    public static Map<String, Object> describe(Object obj) {
        if (obj == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Field f : getAllFields(obj.getClass())) {
            try {
                map.put(f.getName(), f.get(obj));
            } catch (IllegalAccessException e) {
                throw new ReflectionRuntimeException("Failed to read field: " + f.getName(), e);
            }
        }
        return map;
    }

    // == CGLIB 代理处理 ==

    /**
     * 生成对象的简短字符串表示(类名@哈希码)
     * @param o 目标对象
     * @return 简短字符串,null 时返回 "null"
     */
    public static String toShortString(Object o) {
        if (o == null) {
            return "null";
        }
        Class<?> c = o.getClass();
        return c.getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    // == 类加载 ==

    /**
     * 获取对象的真实用户类如果是 CGLIB 代理(类名含 "$$"),返回其父类
     * @param instance 目标对象
     * @return 用户定义的原始类
     * @throws IllegalArgumentException 如果 instance 为 null
     */
    public static Class<?> getUserClass(Object instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance 不能为 null");
        }
        Class<?> clazz = instance.getClass();
        if (clazz.getName().contains("$$")) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && !Object.class.equals(superClass)) {
                return superClass;
            }
        }
        return clazz;
    }

    /**
     * 获取类加载器(优先当前线程上下文加载器,否则使用本类加载器)
     * @return 类加载器
     */
    public static ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ReflectUtil.class.getClassLoader();
    }

    // == 私有方法 ==

    /**
     * 根据完全限定类名加载类
     * @param className 完全限定类名
     * @return 类对象
     * @throws ReflectionRuntimeException 如果类不存在
     */
    public static Class<?> forName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new ReflectionRuntimeException("类不存在: " + className, e);
        }
    }

    /**
     * 设置反射对象为可访问状态(绕过访问权限检查)
     */
    private static void makeAccessible(AccessibleObject ao) {
        if (ao != null && !ao.isAccessible()) {
            ao.setAccessible(true);
            if (ao instanceof Field field) {
                // 移除 字段 final 修饰符
                try {
                    Field modifiersField = Field.class.getDeclaredField("modifiers");
                    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
                } catch (NoSuchFieldException | IllegalAccessException _) {

                }
            }
        }
    }

    /**
     * 根据实际参数类型查找最佳匹配的方法 优先返回参数类型精确匹配的方法,否则返回第一个兼容的方法
     */
    private static Method findBestMatchingMethod(Class<?> cls, String methodName, Object[] args) {
        List<Method> candidates = new ArrayList<>();
        for (Class<?> search = cls; search != null && search != Object.class; search = search.getSuperclass()) {
            for (Method m : search.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && isArgsCompatible(m.getParameterTypes(), args)) {
                    candidates.add(m);
                }
            }
        }
        if (cls != null) {
            for (Class<?> iface : cls.getInterfaces()) {
                for (Method m : iface.getDeclaredMethods()) {
                    if (m.getName().equals(methodName) && isArgsCompatible(m.getParameterTypes(), args)) {
                        candidates.add(m);
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        for (Method m : candidates) {
            if (isExactMatch(m.getParameterTypes(), args)) {
                makeAccessible(m);
                return m;
            }
        }
        Method m = candidates.getFirst();
        makeAccessible(m);
        return m;
    }

    /**
     * 检查实际参数是否与方法参数类型兼容
     */
    private static boolean isArgsCompatible(Class<?>[] paramTypes, Object[] args) {
        int argLen = (args == null ? 0 : args.length);
        if (paramTypes.length != argLen) {
            return false;
        }
        if (argLen == 0) {
            return true;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] == null) {
                if (paramTypes[i].isPrimitive()) {
                    return false;
                }
            } else {
                if (!wrapPrimitive(paramTypes[i]).isAssignableFrom(args[i].getClass())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 检查实际参数是否与方法参数类型精确匹配
     */
    private static boolean isExactMatch(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != (args == null ? 0 : args.length)) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] == null || paramTypes[i] != args[i].getClass()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将基本类型包装为对应的包装类型,非基本类型直接返回
     */
    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }

    /**
     * 从泛型参数数组中解析指定位置的实际类型
     */
    private static Class<?> resolveGenericArg(Type[] args, int index) {
        if (index < 0 || index >= args.length) {
            return Object.class;
        }
        Type arg = args[index];
        if (arg instanceof Class<?> c) {
            return c;
        }
        if (arg instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }

    // == 内部异常类 ==

    /**
     * 将参数值转换为目标类型
     */
    private static Object convertArg(Object value, Class<?> targetType) {
        String s = value.toString();
        if (targetType == String.class) {
            if (s.endsWith(".0")) {
                s = s.substring(0, s.length() - 2);
            }
            return s;
        }
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.valueOf(s);
        }
        if (targetType == Long.class || targetType == long.class) {
            return Long.valueOf(s);
        }
        if (targetType == Double.class || targetType == double.class) {
            return Double.valueOf(s);
        }
        if (targetType == Float.class || targetType == float.class) {
            return Float.valueOf(s);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return value;
    }

    /**
     * @return 具有全部访问权限的可信 lookup
     */

    public static MethodHandles.Lookup lookup() {
        if (trustedLookup != null) {
            return trustedLookup;
        }
        try {
            Constructor<MethodHandles.Lookup> lookupCtor = MethodHandles.Lookup.class
                    .getDeclaredConstructor(Class.class);
            Constructor<?> ctor = ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(MethodHandles.Lookup.class, lookupCtor);
            MethodHandles.Lookup lookup = (MethodHandles.Lookup) ctor.newInstance(MethodHandles.class);
            trustedLookup = (MethodHandles.Lookup) lookup
                    .findStaticGetter(MethodHandles.Lookup.class, "IMPL_LOOKUP", MethodHandles.Lookup.class)
                    .invokeExact();
            return trustedLookup;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * @param declaringClass 声明该方法的类
     * @param name 方法名
     * @param args 方法的参数类型
     * @return 该类中指定方法对应的 {@link Method} 对象
     * @throws NoSuchMethodException 当找不到与指定名称和参数签名匹配的方法时抛出
     */

    public static Method getDeclaredMethod(Class<?> declaringClass, String name, Class<?>... args)
            throws NoSuchMethodException {
        Method method = declaringClass.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    /**
     * @param instance 字段所属实例
     * @param fieldName 要设置值的字段名
     * @param value 要设置的值
     * @param <T> 假定的字段类型
     */
    public static <T> void quietSet(Object instance, String fieldName, T value) {
        Field field = getDeclaredField(instance.getClass(), fieldName);
        quietSet(instance, field, value);
    }

    /**
     * @param instance 字段所属实例
     * @param field 要设置值的字段
     * @param value 要设置的值
     * @param <T> 假定的字段类型
     */
    @SuppressWarnings("unchecked")
    public static <T> void quietSet(Object instance, Field field, T value) {
        try {
            ThrowableSetter<T> setter = (ThrowableSetter<T>) SETTERS.getOrDefault(field.getType(), DEFAULT_SETTER);
            setter.set(field, instance, value);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Setter failure: " + instance.getClass() + "." + field.getName(), ex);
        }
    }

    /**
     * @param instance 字段所属实例
     * @param fieldName 要读取值的字段名
     * @param <T> 假定的字段类型
     * @return 字段值
     */
    public static <T> T quietGet(Object instance, String fieldName) {
        Field field = getDeclaredField(instance.getClass(), fieldName);
        return quietGet(instance, field);
    }

    /**
     * @param instance 字段所属实例
     * @param field 要读取值的字段
     * @param <T> 假定的字段类型
     * @return 字段值
     */
    @SuppressWarnings("unchecked")

    public static <T> T quietGet(Object instance, Field field) {
        try {
            ThrowableGetter<T> getter = (ThrowableGetter<T>) GETTERS.getOrDefault(field.getType(), DEFAULT_GETTER);
            return getter.get(field, instance);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Getter failure: " + instance.getClass() + "." + field.getName(), ex);
        }
    }

    /**
     * @param type 要构造的类
     * @param argTypes 参数类型
     * @param args 参数值
     * @param <T> 假定的类类型
     * @return 该类的新实例
     */

    public static <T> T quietNew(Class<T> type, Class<?>[] argTypes, Object[] args) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor(argTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Constructor failure: " + type.getName(), ex);
        }
    }

    /**
     * @param type 定义该方法的类
     * @param instance 调用所在的实例; 静态方法则为 {@code null}
     * @param name 方法名
     * @param argTypes 参数类型
     * @param args 参数值
     * @param <T> 假定的类类型
     * @return 调用的返回值
     */
    @SuppressWarnings("unchecked")

    public static <T> T quietInvoke(Class<?> type, Object instance, String name, Class<?>[] argTypes, Object[] args) {
        try {
            Method method = type.getDeclaredMethod(name, argTypes);
            method.setAccessible(true);
            return (T) method.invoke(instance, args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Invoke failure: " + type.getName(), ex);
        }
    }

    /**
     * 将 'from' 中的字段值复制到 'to'
     * @param from 提供待复制值的实例
     * @param to 复制值的目标实例
     */
    public static void copyTo(Object from, Object to) {
        if (from == null || to == null) {
            return;
        }
        // 类型必须一致
        Class<?> type = to.getClass();
        if (!type.equals(from.getClass())) {
            return;
        }
        // 将 'from' 中的字段值复制到 'to'
        for (Field field : type.getDeclaredFields()) {
            if ((field.getModifiers() & Modifier.STATIC) > 0) {
                continue;
            }
            field.setAccessible(true);
            Object value = quietGet(from, field);
            quietSet(to, field, value);
        }
    }

    /**
     * 传播抛出的异常
     * @param t 要传播的异常
     */
    @SuppressWarnings("unchecked")
    public static <X extends Throwable> void propagate(Throwable t) throws X {
        throw (X) t;
    }

    /**
     * @param field 要读取的字段
     * @param instance 类的实例
     * @return 该实例中的字段值
     * @throws IllegalAccessException 当无法访问该字段时抛出
     */

    private static Object get(Field field, Object instance) throws IllegalAccessException {
        field.setAccessible(true);
        return field.get(instance);
    }

    /**
     * @param field 要设置的字段
     * @param instance 类的实例
     * @param value 要设置的值
     * @throws IllegalAccessException 当无法访问该字段时抛出
     */
    private static void set(Field field, Object instance, Object value) throws IllegalAccessException {
        field.setAccessible(true);
        field.set(instance, value);
    }

    /**
     * 字段写入操作的函数式接口
     *
     * @param <T> 值类型
     */
    interface ThrowableSetter<T> {

        void set(Field field, Object instance, T value) throws IllegalAccessException;

    }

    /**
     * 字段读取操作的函数式接口
     *
     * @param <T> 值类型
     */
    interface ThrowableGetter<T> {

        T get(Field field, Object instance) throws IllegalAccessException;

    }

    /**
     * 反射操作运行时异常,用于包装反射过程中抛出的受检异常
     */
    public static class ReflectionRuntimeException extends RuntimeException {

        public ReflectionRuntimeException(String message) {
            super(message);
        }

        public ReflectionRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
