package com.bingbaihanji.bdec.decompiler.utils.reflect;


import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 虚拟文件系统抽象,提供应用服务器内的类资源扫描能力
 *
 * @author bingbaihanji
 * @date 2026-06-10
 */
public abstract class VFS {

    /** 内置 VFS 实现类列表,按优先级排列:JBoss6 → Default */
    public static final Class<?>[] IMPLEMENTATIONS = {JBoss6VFS.class, DefaultVFS.class};

    /** 用户自定义 VFS 实现类列表,优先级高于内置实现 */
    public static final List<Class<? extends VFS>> USER_IMPLEMENTATIONS = new ArrayList<>();

//    private static final Logger log = LoggerFactory.getLogger(VFS.class);

    /** VFS 单例实例,首次调用 getInstance() 时初始化 */
    private static VFS instance;

    /**
     * 获取单例 VFS 实例按顺序尝试 USER_IMPLEMENTATIONS 和 IMPLEMENTATIONS,返回第一个可用的实现
     * @return 可用的 VFS 实例,若所有实现均无效则返回 null
     */
    @SuppressWarnings("unchecked")
    public static synchronized VFS getInstance() {
        if (instance != null) {
            return instance;
        }

        List<Class<? extends VFS>> impls = new ArrayList<>();
        impls.addAll(USER_IMPLEMENTATIONS);
        impls.addAll(Arrays.asList((Class<? extends VFS>[]) IMPLEMENTATIONS));

        for (Class<? extends VFS> impl : impls) {
            try {
                VFS vfs = impl.getDeclaredConstructor().newInstance();
                if (vfs.isValid()) {
//                    if (log.isDebugEnabled()) {
//                        log.debug("使用 VFS 适配器 {}", vfs.getClass().getName());
//                    }
                    VFS.instance = vfs;
                    return vfs;
                }
//                if (log.isDebugEnabled()) {
//                    log.debug("VFS 实现 {} 在当前环境无效", impl.getName());
//                }
            } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                     | InvocationTargetException | NoSuchMethodException | SecurityException e) {
//                log.warn("实例化 VFS 失败,继续尝试下一个实现: {}", impl, e);
            }
        }
//        log.warn("未找到可用的 VFS 实现");
        return null;
    }

    /**
     * 添加自定义 VFS 实现类,该实现将优先于内置实现被尝试
     * @param clazz 自定义 VFS 实现类,为 null 时忽略
     */
    public static void addImplClass(Class<? extends VFS> clazz) {
        if (clazz != null) {
            USER_IMPLEMENTATIONS.add(clazz);
        }
    }

    /**
     * 按类名加载 Class,找不到返回 null
     */
    protected static Class<?> getClass(String className) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = VFS.class.getClassLoader();
        }
        try {
            return classLoader == null ? Class.forName(className) : classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
//            if (log.isDebugEnabled()) {
//                log.debug("类未找到: {}", className);
//            }
            return null;
        }
    }

    /**
     * 按方法名和参数类型查找 Method,找不到返回 null
     */
    protected static Method getMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            if (clazz == null) {
                return null;
            }
            return clazz.getMethod(methodName, parameterTypes);
        } catch (SecurityException | NoSuchMethodException e) {
//            log.error("查找方法失败: {}.{}", clazz.getName(), methodName, e);
            return null;
        }
    }

    /**
     * 反射调用方法,若目标方法抛出 IOException 则原样抛出
     */
    @SuppressWarnings("unchecked")
    protected static <T> T invoke(Method method, Object object, Object... parameters) throws IOException {
        try {
            return (T) method.invoke(object, parameters);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException("反射调用方法失败: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof IOException ie) {
                throw ie;
            }
            throw new RuntimeException("反射调用方法失败: " + method.getName(), e);
        }
    }

    /**
     * 获取指定路径的所有资源 URL
     */
    protected static List<URL> getResources(String path) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = VFS.class.getClassLoader();
        }
        if (classLoader == null) {
            return Collections.list(ClassLoader.getSystemResources(path));
        }
        return Collections.list(classLoader.getResources(path));
    }

    /**
     * @return 当前 VFS 实现是否可用
     */
    public abstract boolean isValid();

    /**
     * 递归列出指定 URL 下的所有子资源
     */
    protected abstract List<String> list(URL url, String forPath) throws IOException;

    /**
     * 递归列出指定路径下的所有子资源
     */
    public List<String> list(String path) throws IOException {
        List<String> names = new ArrayList<>();
        for (URL url : getResources(path)) {
            names.addAll(list(url, path));
        }
        return names;
    }

}
