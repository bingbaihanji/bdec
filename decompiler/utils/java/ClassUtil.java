package com.bingbaihanji.bdec.decompiler.utils.java;


import com.bingbaihanji.bdec.decompiler.utils.collection.ArrayMap;
import com.bingbaihanji.bdec.decompiler.utils.reflect.ReflectUtil;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 类与反射相关工具类
 * <p>
 * 提供包类扫描,类元数据判定,类型转换以及常用反射增强操作
 * 支持从文件系统目录和 Jar 包中按自定义过滤器加载类,广泛适用于框架初始化,
 * 插件机制,动态代理等场景
 * </p>
 *
 * <p>主要功能模块: </p>
 * <ul>
 *   <li>包扫描: 按过滤器,父类/接口,注解等条件发现类</li>
 *   <li>类判定: 抽象/接口,基本类型/包装类型,内部类判断</li>
 *   <li>反射增强: 递归查找 Field/Method,安全实例化,获取类加载器</li>
 * </ul>
 *
 * @author bingbaihanji
 * @date 2026-06-10
 */
public class ClassUtil {

//    private static final Logger log = LoggerFactory.getLogger(ClassUtil.class);

    /** 类文件后缀 */
    private static final String CLASS_FILE_SUFFIX = ".class";

    /** 文件系统协议标识 */
    private static final String PROTOCOL_FILE = "file";

    /** Jar 包协议标识 */
    private static final String PROTOCOL_JAR = "jar";

    /** 基本类型与其包装类型的映射字典 */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_MAP = new ArrayMap<>(8);

    static {
        PRIMITIVE_WRAPPER_MAP.put(boolean.class, Boolean.class);
        PRIMITIVE_WRAPPER_MAP.put(byte.class, Byte.class);
        PRIMITIVE_WRAPPER_MAP.put(char.class, Character.class);
        PRIMITIVE_WRAPPER_MAP.put(double.class, Double.class);
        PRIMITIVE_WRAPPER_MAP.put(float.class, Float.class);
        PRIMITIVE_WRAPPER_MAP.put(int.class, Integer.class);
        PRIMITIVE_WRAPPER_MAP.put(long.class, Long.class);
        PRIMITIVE_WRAPPER_MAP.put(short.class, Short.class);
    }

    /** 私有化构造器,禁止实例化静态工具类 */
    private ClassUtil() {
        throw new AssertionError("工具类不允许实例化");
    }

    // ==========================================
    // 1. 包扫描相关 API (Class Scanning)
    // ==========================================

    /**
     * 扫描指定包路径下的所有类
     *
     * @param packageNames 目标包名列表(支持变长参数,如 "com.example.service")
     * @return 匹配到的 Class 集合(不保证顺序)
     */
    public static Set<Class<?>> scanAllClasses(String... packageNames) {
        return findClassesWithFilter(clazz -> true, packageNames);
    }

    /**
     * 查询指定父类或父接口的实现类/子类
     * 自动排除父类/父接口本身以及抽象类和接口
     *
     * @param superClass   父类或父接口(不能为 null)
     * @param packageNames 要扫描的包名列表
     * @param <T>          父类型
     * @return 所有直接或间接子类/实现类的集合;若 superClass 为 null 则返回空集合
     */
    @SuppressWarnings("unchecked")
    public static <T> Set<Class<? extends T>> findSubclasses(Class<T> superClass, String... packageNames) {
        if (superClass == null) {
            return Collections.emptySet();
        }
        // 过滤条件: 是目标类的子类/实现类,且不等于自身,同时不是抽象类或接口
        Predicate<Class<?>> filter = clazz -> superClass.isAssignableFrom(clazz)
                && !superClass.equals(clazz)
                && !isAbstractOrInterface(clazz);

        Set<Class<?>> rawClasses = findClassesWithFilter(filter, packageNames);
        Set<Class<? extends T>> result = new HashSet<>();
        for (Class<?> clazz : rawClasses) {
            // 过滤条件已保证类型安全,可直接强转
            result.add((Class<? extends T>) clazz);
        }
        return result;
    }

    /**
     * 查询包含指定注解的类
     *
     * @param annotationClass 目标注解类型(不能为 null)
     * @param packageNames    要扫描的包名列表
     * @return 标注了该注解的类集合;若 annotationClass 为 null 则返回空集合
     */
    public static Set<Class<?>> findAnnotatedClasses(Class<? extends Annotation> annotationClass, String... packageNames) {
        if (annotationClass == null) {
            return Collections.emptySet();
        }
        return findClassesWithFilter(clazz -> clazz.isAnnotationPresent(annotationClass), packageNames);
    }

    /**
     * 根据自定义过滤器扫描指定包下的类
     * <p>
     * 支持同时扫描多个包,自动识别文件系统和 Jar 包内的类
     * 类加载时会跳过静态初始化(initialize = false),以提升扫描性能
     * </p>
     *
     * @param filter       类过滤器,返回 true 表示保留该类
     * @param packageNames 包名列表(不能为 null 或空)
     * @return 通过过滤器的 Class 集合
     */
    public static Set<Class<?>> findClassesWithFilter(Predicate<Class<?>> filter, String... packageNames) {
        if (packageNames == null || packageNames.length == 0) {
            return Collections.emptySet();
        }

        Set<Class<?>> classes = new HashSet<>();
        ClassLoader classLoader = getDefaultClassLoader();

        for (String packageName : packageNames) {
            if (packageName == null || packageName.trim().isEmpty()) {
                continue;
            }
            // 将包名转换为路径形式
            String packagePath = packageName.trim().replace('.', '/');
            try {
                Enumeration<URL> resources = classLoader.getResources(packagePath);
                while (resources.hasMoreElements()) {
                    URL url = resources.nextElement();
                    String protocol = url.getProtocol();

                    if (PROTOCOL_FILE.equals(protocol)) {
                        // 文件系统路径,处理空格和特殊字符
                        String filePath = URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8);
                        findFileDirectory(new File(filePath), packageName, classLoader, filter, classes);
                    } else if (PROTOCOL_JAR.equals(protocol)) {
                        scanJarFile(url, packagePath, classLoader, filter, classes);
                    }
                    // 其他协议(如 war,vfs 等)暂不处理,可扩展
                }
            } catch (Exception e) {
                // log.error("扫描包路径 [{}] 时发生异常", packageName, e);
            }
        }
        return classes;
    }

    /**
     * 递归扫描文件系统目录下的 .class 文件
     *
     * @param dir         当前目录
     * @param packageName 当前目录对应的包名
     * @param classLoader 类加载器
     * @param filter      类过滤器
     * @param classes     结果集合
     */
    private static void findFileDirectory(File dir, String packageName, ClassLoader classLoader,
                                          Predicate<Class<?>> filter, Set<Class<?>> classes) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                // 递归处理子目录,包名追加子目录名
                findFileDirectory(file, packageName + "." + file.getName(), classLoader, filter, classes);
            } else if (file.getName().endsWith(CLASS_FILE_SUFFIX)) {
                // 去掉后缀得到类名(支持内部类如 Outer$Inner.class)
                String className = packageName + "."
                        + file.getName().substring(0, file.getName().length() - CLASS_FILE_SUFFIX.length());
                loadAndCheckClass(className, classLoader, filter, classes);
            }
        }
    }

    /**
     * 扫描 Jar 包内指定路径下的 .class 条目
     *
     * @param jarUrl      Jar 资源的 URL
     * @param packagePath 包路径(以 / 分隔)
     * @param classLoader 类加载器
     * @param filter      类过滤器
     * @param classes     结果集合
     * @throws IOException 读取 Jar 文件时可能抛出 IO 异常
     */
    private static void scanJarFile(URL jarUrl, String packagePath, ClassLoader classLoader,
                                    Predicate<Class<?>> filter, Set<Class<?>> classes) throws IOException {
        // 增加 instanceof 检查,兼容部分容器非标准的 Jar URL 协议实现
        if (!(jarUrl.openConnection() instanceof JarURLConnection jarURLConnection)) {
            // log.warn("无法建立 JarURLConnection: {}", jarUrl);
            return;
        }

        try (JarFile jarFile = jarURLConnection.getJarFile()) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // 去掉某些 Jar 包中条目开头的 "/"
                if (entryName.startsWith("/")) {
                    entryName = entryName.substring(1);
                }

                // 仅处理指定包路径下的非目录 .class 文件
                if (entryName.startsWith(packagePath) && entryName.endsWith(CLASS_FILE_SUFFIX) && !entry.isDirectory()) {
                    // 路径转包名,去掉后缀
                    String className = entryName.replace('/', '.')
                            .substring(0, entryName.length() - CLASS_FILE_SUFFIX.length());
                    loadAndCheckClass(className, classLoader, filter, classes);
                }
            }
        }
    }

    /**
     * 加载类并进行过滤检查,成功则加入结果集合
     *
     * @param className   全限定类名
     * @param classLoader 类加载器
     * @param filter      类过滤器(可为 null,表示不过滤)
     * @param classes     结果集合
     */
    private static void loadAndCheckClass(String className, ClassLoader classLoader,
                                          Predicate<Class<?>> filter, Set<Class<?>> classes) {
        try {
            // initialize = false: 不触发类的静态初始化,避免不必要的副作用
            Class<?> clazz = Class.forName(className, false, classLoader);
            if (filter == null || filter.test(clazz)) {
                classes.add(clazz);
            }
        } catch (Throwable t) {
            // 忽略所有加载异常(如 NoClassDefFoundError,ClassNotFoundException),保证扫描不被个别类中断
//            if (log.isTraceEnabled()) {
            // log.trace("加载类 [{}] 失败: {}", className, t.getMessage());
//        }
        }
    }

// ==========================================
// 2. 类元数据判定 API (Class Judgement)
// ==========================================

    /**
     * 判断目标类是否为抽象类或接口
     *
     * @param clazz 待判断的类
     * @return 若为抽象类或接口返回 true;clazz 为 null 时返回 false
     */
    public static boolean isAbstractOrInterface(Class<?> clazz) {
        return clazz != null && (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers()));
    }


    /**
     * 判断是否为内部类(非静态内部类)
     *
     * @param clazz 待判断的类
     * @return true 表示是成员内部类(匿名类,局部类也可能视为内部类)
     */
    public static boolean isInnerClass(Class<?> clazz) {
        return clazz != null && clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers());
    }


// ==========================================
// 3. 常用反射增强 API (Reflection Helpers)
// ==========================================

    /**
     * 递归向上查找指定类及其所有父类中声明的字段(包含私有字段)
     * 找到后自动设置 {@link Field#setAccessible(boolean)} 为 true
     *
     * @param clazz     起始类
     * @param fieldName 字段名称
     * @return 可访问的 Field 对象;若未找到则返回 null
     */
    public static Field getDeclaredField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // 未找到,向父类继续查找
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 递归向上查找指定类及其所有父类中声明的指定方法(包含私有方法)
     * 找到后自动设置 {@link Method#setAccessible(boolean)} 为 true
     *
     * @param clazz          起始类
     * @param methodName     方法名
     * @param parameterTypes 方法参数类型
     * @return 可访问的 Method 对象;若未找到则返回 null
     */
    public static Method getDeclaredMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return ReflectUtil.getDeclaredMethod(current, methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                // 未找到,向父类继续查找
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 安全实例化对象(调用无参构造器)
     * 自动处理私有构造器的访问权限,适合单例,工具类等场景
     *
     * @param clazz 目标类 Class
     * @param <T>   目标类型
     * @return 实例化后的对象
     * @throws IllegalArgumentException 如果 clazz 为 null
     * @throws RuntimeException          当无参构造器不存在或实例化过程中发生异常时抛出
     */
    public static <T> T newInstance(Class<T> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class must not be null");
        }
        try {
            Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("无法通过无参构造器实例化类: " + clazz.getName(), e);
        }
    }

    /**
     * 获取当前可用的 ClassLoader
     * <p>优先级: 线程上下文类加载器 &gt; 当前类的类加载器 &gt; 系统类加载器</p>
     *
     * @return 非 null 的 ClassLoader
     */
    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
            // 忽略安全策略等异常
        }
        if (cl == null) {
            cl = ClassUtil.class.getClassLoader();
            if (cl == null) {
                try {
                    cl = ClassLoader.getSystemClassLoader();
                } catch (Throwable ex) {
                    // 理论上不会为 null,兜底返回当前类的类加载器
                }
            }
        }
        return cl;
    }
}