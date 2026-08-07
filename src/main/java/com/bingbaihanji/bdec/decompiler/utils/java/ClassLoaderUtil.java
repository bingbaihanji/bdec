package com.bingbaihanji.bdec.decompiler.utils.java;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ClassLoader 工具类
 * <p>
 * 提供 ClassLoader 的安全获取,类加载(支持基本类型及数组),
 * 资源(Resource/Stream)获取以及双亲委派机制下的多 ClassLoader 处理等生产常用功能 
 * </p>
 *
 * @author bingbaihanji
 * @date 2026-06-10
 */
public class ClassLoaderUtil {


    /** 基本数据类型名称与其 Class 对象的映射字典 */
    private static final Map<String, Class<?>> PRIMITIVE_TYPE_NAME_MAP = new HashMap<>(16);

    static {
        List<Class<?>> primitiveTypes = Arrays.asList(
                boolean.class, byte.class, char.class, double.class,
                float.class, int.class, long.class, short.class, void.class
        );
        for (Class<?> clazz : primitiveTypes) {
            PRIMITIVE_TYPE_NAME_MAP.put(clazz.getName(), clazz);
        }
    }

    private ClassLoaderUtil() {
        throw new AssertionError("工具类不允许实例化");
    }

    // ==========================================
    // 1. ClassLoader 获取 API
    // ==========================================

    /**
     * 获取当前线程上下文 ClassLoader
     *
     * @return 当前线程的 ContextClassLoader,若获取失败或为 null,则返回 null
     */
    public static ClassLoader getContextClassLoader() {
        try {
            return Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
            // log.warn("无法获取当前线程上下文 ClassLoader: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 获取系统 ClassLoader(AppClassLoader)
     *
     * @return 系统 ClassLoader,若发生 SecurityException 则返回 null
     */
    public static ClassLoader getSystemClassLoader() {
        try {
            return ClassLoader.getSystemClassLoader();
        } catch (Throwable ex) {
            // log.warn("无法获取系统 ClassLoader: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 获取指定类的 ClassLoader
     * <p>
     * 注意:对于 JDK 核心类库(如 java.lang.String),此方法返回 BootStrap ClassLoader (null) 
     * </p>
     *
     * @param clazz 目标类
     * @return 该类的 ClassLoader,若类为 null 或由 Bootstrap 加载则返回 null
     */
    public static ClassLoader getClassLoader(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        return clazz.getClassLoader();
    }

    /**
     * 获取首选/默认的 ClassLoader
     * <p>
     * 策略优先级:
     * 1. 当前线程上下文 ClassLoader
     * 2. 本工具类的 ClassLoader
     * 3. 系统 ClassLoader
     * </p>
     *
     * @return 非 null 的 ClassLoader 实例
     */
    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = getContextClassLoader();
        if (cl == null) {
            cl = ClassLoaderUtil.class.getClassLoader();
            if (cl == null) {
                cl = getSystemClassLoader();
            }
        }
        return cl;
    }

    // ==========================================
    // 2. 类加载 API (Class Loading)
    // ==========================================

    /**
     * 使用默认 ClassLoader 加载类(默认初始化类)
     *
     * @param className 类名(支持全类名,基本类型,数组如 java.lang.String[])
     * @return 加载到的 Class 对象
     * @throws ClassNotFoundException 如果找不到类
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException {
        return loadClass(className, true, getDefaultClassLoader());
    }

    /**
     * 使用默认 ClassLoader 加载类,允许指定是否初始化类
     *
     * @param className  类名
     * @param initialize 是否在加载时执行 static 代码块/初始化静态变量
     * @return 加载到的 Class 对象
     * @throws ClassNotFoundException 如果找不到类
     */
    public static Class<?> loadClass(String className, boolean initialize) throws ClassNotFoundException {
        return loadClass(className, initialize, getDefaultClassLoader());
    }

    /**
     * 指定 ClassLoader 加载类(增强版,原生支持基本类型和数组类型)
     *
     * @param className  类名
     * @param initialize 是否初始化
     * @param classLoader 指定的 ClassLoader(若为 null,则降级使用默认 ClassLoader)
     * @return 加载到的 Class 对象
     * @throws ClassNotFoundException 如果找不到类
     */
    public static Class<?> loadClass(String className, boolean initialize, ClassLoader classLoader) throws ClassNotFoundException {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Class name must not be empty");
        }

        String name = className.trim();

        // 1. 处理 Java 基本数据类型 (int, boolean, void 等)
        if (PRIMITIVE_TYPE_NAME_MAP.containsKey(name)) {
            return PRIMITIVE_TYPE_NAME_MAP.get(name);
        }

        ClassLoader clToUse = (classLoader != null) ? classLoader : getDefaultClassLoader();

        // 2. 处理数组类型 (支持 "java.lang.String[]" 或 "[Ljava.lang.String;" 格式)
        if (name.endsWith("[]")) {
            String elementClassName = name.substring(0, name.length() - 2);
            Class<?> elementClass = loadClass(elementClassName, initialize, clToUse);
            return java.lang.reflect.Array.newInstance(elementClass, 0).getClass();
        }

        // 3. 通用 Class.forName 加载
        try {
            return Class.forName(name, initialize, clToUse);
        } catch (ClassNotFoundException ex) {
            // 4. 容错回退:尝试用系统 ClassLoader 兜底
            ClassLoader sysCl = getSystemClassLoader();
            if (sysCl != null && sysCl != clToUse) {
                return Class.forName(name, initialize, sysCl);
            }
            throw ex;
        }
    }

    /**
     * 判断某个类在指定的 ClassLoader 下是否存在
     *
     * @param className  类名
     * @param classLoader 指定的 ClassLoader
     * @return 是否能成功加载该类
     */
    public static boolean isPresent(String className, ClassLoader classLoader) {
        try {
            loadClass(className, false, classLoader);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    // ==========================================
    // 3. 资源(Resource)定位与读取 API
    // ==========================================

    /**
     * 获取类路径下的资源 URL
     *
     * @param resourcePath 相对路径(如 "config/app.properties" 或 "/config/app.properties")
     * @param classLoader  指定 ClassLoader,可为 null
     * @return 资源 URL,不存在时返回 null
     */
    public static URL getResource(String resourcePath, ClassLoader classLoader) {
        if (resourcePath == null) {
            return null;
        }

        String path = cleanResourcePath(resourcePath);
        ClassLoader clToUse = (classLoader != null) ? classLoader : getDefaultClassLoader();

        URL url = clToUse.getResource(path);

        // 如果未查找到,尝试使用 ClassLoaderUtil 本身的 ClassLoader 重新查找
        if (url == null && clToUse != ClassLoaderUtil.class.getClassLoader()) {
            clToUse = ClassLoaderUtil.class.getClassLoader();
            if (clToUse != null) {
                url = clToUse.getResource(path);
            }
        }

        // 最后降级尝试 Bootstrap ClassLoader
        if (url == null) {
            url = ClassLoader.getSystemResource(path);
        }

        return url;
    }

    /**
     * 获取资源的 InputStream 输入流
     *
     * @param resourcePath 资源路径
     * @param classLoader  指定 ClassLoader,可为 null
     * @return 资源输入流,找不到则返回 null(注意:调用方负责关闭该流)
     */
    public static InputStream getResourceAsStream(String resourcePath, ClassLoader classLoader) {
        URL url = getResource(resourcePath, classLoader);
        if (url == null) {
            return null;
        }
        try {
            return url.openStream();
        } catch (Exception e) {
            // log.warn("无法打开资源流: [{}]", resourcePath, e);
            return null;
        }
    }

    /**
     * 获取类路径下所有同名资源的 URL 集合(常用于 SPI 机制或插件合并场景)
     *
     * @param resourcePath 资源路径
     * @param classLoader  指定 ClassLoader
     * @return URL 列表
     */
    public static List<URL> getResources(String resourcePath, ClassLoader classLoader) {
        List<URL> result = new ArrayList<>();
        if (resourcePath == null) {
            return result;
        }

        String path = cleanResourcePath(resourcePath);
        ClassLoader clToUse = (classLoader != null) ? classLoader : getDefaultClassLoader();

        try {
            Enumeration<URL> urls = clToUse.getResources(path);
            while (urls.hasMoreElements()) {
                result.add(urls.nextElement());
            }
        } catch (Exception e) {
            // log.error("获取同名资源失败: [{}]", resourcePath, e);
        }

        return result;
    }

    // ==========================================
    // 4. 辅助私有方法
    // ==========================================

    /**
     * 规范化资源路径(剔除开头的 '/',因为 ClassLoader.getResource 不需要斜杠开头)
     */
    private static String cleanResourcePath(String path) {
        String resourcePath = path.trim();
        if (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }
        return resourcePath;
    }
}