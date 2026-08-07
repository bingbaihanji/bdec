package com.bingbaihanji.bdec.decompiler.utils.java;


import com.bingbaihanji.bdec.decompiler.utils.collection.ArraySet;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 生产级动态类加载器, 用于加载外部 Jar 和类路径
 *
 * <p>主要功能
 * <ul>
 *   <li>从目录/单个 Jar/Jar 目录创建隔离的 {@link URLClassLoader}</li>
 *   <li>将 Jar 动态注入已有的 {@link URLClassLoader} 或系统类加载器</li>
 *   <li>支持 .class 文件目录和 .jar 文件混合加载</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 实例方法加锁保证内部一致性
 *
 * <h3>资源释放</h3>
 * 实现 {@link Closeable}, 使用完成后需调用 {@link #close()} 释放资源, 关闭后不允许再添加 URL
 *
 * <h3>JDK 9+ 模块系统</h3>
 * 反射调用 {@code URLClassLoader.addURL} 需添加 JVM 参数 {@code --add-opens java.base/java.net=ALL-UNNAMED}
 * 若未添加, 静态注入方法会打印错误并返回 0
 * 仅当系统类加载器是 {@code URLClassLoader} 实例时(如 JDK 8)才能注入, 否则记录警告并返回 false
 *
 * @author bingbaihanji
 * @since 2026-06-10
 */
public class JarClassLoader extends URLClassLoader implements Closeable {

//    private static final Logger log = LoggerFactory.getLogger(JarClassLoader.class);

    private static final String JAR_EXT = ".jar";

    // 反射获取的 URLClassLoader.addURL 方法, 用于向现有类加载器注入 Jar
    private static final Method ADD_URL_METHOD;

    static {
        Method method = null;
        try {
            method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
        } catch (NoSuchMethodException | SecurityException e) {
            // log.error("无法获取可访问的 addURL 方法(若在 JDK 9+ 环境下运行,请检查是否配置了 --add-opens 参数)", e);
        }
        ADD_URL_METHOD = method;
    }

    // 记录已添加的 URL, 防止重复添加
    private final Set<URL> addedUrls = new ArraySet<>();

    private volatile boolean closed = false;

    // ==========================================
    // 1. 静态工厂与注入辅助方法
    // ==========================================

    /**
     * 使用默认父加载器创建实例(优先线程上下文类加载器)
     */
    public JarClassLoader() {
        this(new URL[0]);
    }

    /**
     * 使用指定 URL 数组和默认父加载器创建实例
     *
     * @param urls 初始 URL 数组
     */
    public JarClassLoader(URL[] urls) {
        this(urls, getDefaultClassLoader());
    }

    /**
     * 使用指定 URL 数组和父加载器创建实例
     *
     * @param urls   初始 URL 数组
     * @param parent 父类加载器
     */
    public JarClassLoader(URL[] urls, ClassLoader parent) {
        super(urls != null ? urls : new URL[0], parent);
        // 关键修复:同步初始化 addedUrls 集合,防止构造函数传入的 URL 在后续 addURL 时被重复添加
        if (urls != null) {
            for (URL url : urls) {
                if (url != null) {
                    this.addedUrls.add(url);
                }
            }
        }
    }

    /**
     * 创建新的 JarClassLoader 并从给定的类路径入口加载资源
     *
     * @param classpathEntry 根目录或 Jar 文件, 不能为 null
     * @return 新的 JarClassLoader 实例
     * @throws NullPointerException 如果 classpathEntry 为 null
     */
    public static JarClassLoader createFromClasspath(File classpathEntry) {
        Objects.requireNonNull(classpathEntry, "classpathEntry 不能为 null");
        JarClassLoader loader = new JarClassLoader();
        if (classpathEntry.exists()) {
            if (classpathEntry.isDirectory()) {
                loader.addJar(classpathEntry);               // 递归加载所有 .jar
                loader.addClasspathEntry(classpathEntry);    // 加载目录下的 .class 文件
            } else {
                loader.addClasspathEntry(classpathEntry);
            }
        } else {
            // log.warn("类路径入口不存在: {}", classpathEntry.getAbsolutePath());
        }
        return loader;
    }

    // ==========================================
    // 2. 构造方法
    // ==========================================

    /**
     * 创建新的 JarClassLoader, 仅加载 Jar 文件(不包含裸 .class 目录)
     *
     * @param jarOrDir Jar 文件或包含 Jar 的目录, 不能为 null
     * @return 新的 JarClassLoader 实例
     * @throws NullPointerException 如果 jarOrDir 为 null
     */
    public static JarClassLoader createFromJars(File jarOrDir) {
        Objects.requireNonNull(jarOrDir, "jarOrDir 不能为 null");
        JarClassLoader loader = new JarClassLoader();
        if (jarOrDir.exists()) {
            loader.addJar(jarOrDir);
        } else {
            // log.warn("Jar/目录不存在: {}", jarOrDir.getAbsolutePath());
        }
        return loader;
    }

    /**
     * 将 Jar 文件动态注入到已有的 {@link URLClassLoader} 中
     *
     * @param loader 目标类加载器, 不能为 null
     * @param path   Jar 文件或包含 Jar 的目录, 不能为 null
     * @return 成功注入的 Jar 数量
     * @throws NullPointerException 如果任一参数为 null
     */
    public static int injectJars(URLClassLoader loader, File path) {
        Objects.requireNonNull(loader, "loader 不能为 null");
        Objects.requireNonNull(path, "path 不能为 null");

        if (ADD_URL_METHOD == null) {
            // log.error("无法注入 Jar: addURL 方法不可用(可能缺少 --add-opens java.base/java.net=ALL-UNNAMED 参数)");
            return 0;
        }
        if (!path.exists()) {
            // log.warn("路径不存在: {}", path.getAbsolutePath());
            return 0;
        }

        List<File> jars = loopJar(path);
        int count = 0;
        // 移除 synchronized(loader),避免多线程类加载时发生死锁
        for (File jar : jars) {
            try {
                URL url = jar.toURI().toURL();
                ADD_URL_METHOD.invoke(loader, url);
                count++;
//                if (log.isDebugEnabled()) {
                // log.debug("已注入 Jar 到类加载器 [{}]: {}", loader.getClass().getName(), jar.getAbsolutePath());
//                }
            } catch (MalformedURLException e) {
                // log.error("Jar 路径转 URL 失败: {}", jar.getAbsolutePath(), e);
            } catch (Exception e) {
                // log.error("注入 Jar 到类加载器 [{}] 失败: {}", loader.getClass().getName(), jar.getAbsolutePath(), e);
            }
        }
        return count;
    }

    /**
     * 将 Jar 文件动态注入到系统类加载器
     *
     * @param path Jar 文件或包含 Jar 的目录, 不能为 null
     * @return 如果至少成功注入一个 Jar 则返回 true
     * @throws NullPointerException 如果 path 为 null
     */
    public static boolean injectJarsToSystemClassLoader(File path) {
        Objects.requireNonNull(path, "path 不能为 null");
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        if (systemClassLoader instanceof URLClassLoader) {
            return injectJars((URLClassLoader) systemClassLoader, path) > 0;
        } else {
            // log.warn("系统类加载器 [{}] 不是 URLClassLoader(常见于 JDK 9+), 无法注入. 建议使用自定义 JarClassLoader 进行隔离加载",
//                    systemClassLoader.getClass().getName());
            return false;
        }
    }

    // ==========================================
    // 3. 核心功能方法
    // ==========================================

    private static List<File> loopJar(File file) {
        if (file == null || !file.exists()) {
            return Collections.emptyList();
        }
        List<File> jarList = new ArrayList<>();
        if (isJarFile(file)) {
            jarList.add(file);
            return jarList;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles(
                    subFile -> subFile.isDirectory() || isJarFile(subFile));
            if (files != null) {
                for (File subFile : files) {
                    if (subFile.isDirectory()) {
                        jarList.addAll(loopJar(subFile));
                    } else {
                        jarList.add(subFile);
                    }
                }
            }
        }
        return jarList;
    }

    private static boolean isJarFile(File file) {
        return file != null
                && file.exists()
                && file.isFile()
                && file.getName().toLowerCase().endsWith(JAR_EXT);
    }

    private static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (SecurityException ignored) {
            // 安全限制时忽略
        }
        if (cl == null) {
            cl = JarClassLoader.class.getClassLoader();
            if (cl == null) {
                cl = ClassLoader.getSystemClassLoader();
            }
        }
        return cl;
    }

    /**
     * 加载 Jar 文件或递归加载目录下所有 Jar 文件
     *
     * @param jarFileOrDir Jar 文件或包含 Jar 的目录
     * @return 实际新增的 Jar 文件数量(去重后)
     * @throws IllegalStateException 如果类加载器已关闭
     */
    public synchronized int addJar(File jarFileOrDir) {
        ensureOpen();
        if (jarFileOrDir == null || !jarFileOrDir.exists()) {
            return 0;
        }

        if (isJarFile(jarFileOrDir)) {
            return addClasspathEntry(jarFileOrDir) ? 1 : 0;
        }

        int count = 0;
        List<File> jars = loopJar(jarFileOrDir);
        for (File jar : jars) {
            if (addClasspathEntry(jar)) {
                count++;
            }
        }
        return count;
    }

    // ==========================================
    // 4. 私有辅助方法
    // ==========================================

    /**
     * 将文件或目录对应的 URL 加入类加载路径
     *
     * @param file 目录或 Jar 文件
     * @return 如果成功添加(且此前未添加过)返回 true
     * @throws IllegalStateException 如果类加载器已关闭
     */
    public synchronized boolean addClasspathEntry(File file) {
        ensureOpen();
        if (file != null && file.exists()) {
            try {
                return addUrlInternal(file.toURI().toURL());
            } catch (MalformedURLException e) {
                // log.error("文件转 URL 失败: {}", file.getAbsolutePath(), e);
            }
        }
        return false;
    }

    /**
     * 添加 URL 到类路径, 覆盖父类方法保证线程安全与去重
     *
     * @param url 要添加的 URL
     * @throws IllegalStateException 如果类加载器已关闭
     */
    @Override
    public synchronized void addURL(URL url) {
        addUrlInternal(url);
    }

    /**
     * 释放底层资源, 关闭后不能再添加新 URL
     */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            super.close();
            addedUrls.clear(); // 释放 URL 集合内存
        }
    }

    private synchronized boolean addUrlInternal(URL url) {
        ensureOpen();
        if (url != null && addedUrls.add(url)) {
            super.addURL(url);  // 调用父类 protected addURL
//            if (log.isDebugEnabled()) {
            // log.debug("已添加 URL 到类路径: {}", url);
//            }
            return true;
        }
        return false;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JarClassLoader 已关闭, 无法修改");
        }
    }
}