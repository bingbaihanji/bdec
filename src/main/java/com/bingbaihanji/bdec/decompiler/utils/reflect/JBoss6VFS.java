package com.bingbaihanji.bdec.decompiler.utils.reflect;


import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JBoss 6 VFS 实现,用于适配 JBoss/WildFly 应用服务器的虚拟文件系统
 *
 * @author bingbaihanji
 * @date 2026-06-10
 */
class JBoss6VFS extends VFS {

//    private static final Logger log = LoggerFactory.getLogger(JBoss6VFS.class);

    /** JBoss VFS API 在当前环境是否可用 */
    private static Boolean valid;

    static {
        initialize();
    }

    protected static synchronized void initialize() {
        if (valid == null) {
            valid = true;
            JbossVFS.vfsClass = checkNotNull(getClass("org.jboss.vfs.VFS"));
            JbossVirtualFile.virtualFileClass = checkNotNull(getClass("org.jboss.vfs.VirtualFile"));
            JbossVFS.getChild = checkNotNull(getMethod(JbossVFS.vfsClass, "getChild", URL.class));
            JbossVirtualFile.getChildrenRecursively = checkNotNull(
                    getMethod(JbossVirtualFile.virtualFileClass, "getChildrenRecursively"));
            JbossVirtualFile.getPathNameRelativeTo = checkNotNull(getMethod(JbossVirtualFile.virtualFileClass,
                    "getPathNameRelativeTo", JbossVirtualFile.virtualFileClass));
            checkReturnType(JbossVFS.getChild, JbossVirtualFile.virtualFileClass);
            checkReturnType(JbossVirtualFile.getChildrenRecursively, List.class);
            checkReturnType(JbossVirtualFile.getPathNameRelativeTo, String.class);
        }
    }

    protected static <T> T checkNotNull(T object) {
        if (object == null) {
            setInvalid();
        }
        return object;
    }

    protected static void checkReturnType(Method method, Class<?> expected) {
        if (method != null && !expected.isAssignableFrom(method.getReturnType())) {
//            log.error("方法 {}.{} 应返回 {} 但返回了 {}", method.getDeclaringClass().getName(), method.getName(),
//                    expected.getName(), method.getReturnType().getName());
            setInvalid();
        }
    }

    protected static void setInvalid() {
        if (JBoss6VFS.valid != null && JBoss6VFS.valid) {
//            if (log.isDebugEnabled()) {
//                log.debug("JBoss 6 VFS API 在当前环境不可用");
//            }
            JBoss6VFS.valid = false;
        }
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public List<String> list(URL url, String path) throws IOException {
        JbossVirtualFile directory = JbossVFS.getChild(url);
        if (directory == null) {
            return Collections.emptyList();
        }
        if (!path.endsWith("/")) {
            path += "/";
        }
        List<JbossVirtualFile> children = directory.getChildren();
        List<String> names = new ArrayList<>(children.size());
        for (JbossVirtualFile vf : children) {
            names.add(path + vf.getPathNameRelativeTo(directory));
        }
        return names;
    }

    /** JBoss VirtualFile 包装类 */
    static class JbossVirtualFile {

        static Class<?> virtualFileClass;

        static Method getPathNameRelativeTo;

        static Method getChildrenRecursively;

        private final Object virtualFile;

        JbossVirtualFile(Object virtualFile) {
            this.virtualFile = virtualFile;
        }

        String getPathNameRelativeTo(JbossVirtualFile parent) {
            try {
                return invoke(getPathNameRelativeTo, virtualFile, parent.virtualFile);
            } catch (IOException e) {
//                log.error("VirtualFile.getPathNameRelativeTo 意外抛出 IOException", e);
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        List<JbossVirtualFile> getChildren() throws IOException {
            List<Object> objects = invoke(getChildrenRecursively, virtualFile);
            List<JbossVirtualFile> children = new ArrayList<>(objects.size());
            for (Object object : objects) {
                children.add(new JbossVirtualFile(object));
            }
            return children;
        }

    }

    /** JBoss VFS 包装类 */
    static class JbossVFS {

        static Class<?> vfsClass;

        static Method getChild;

        static JbossVirtualFile getChild(URL url) throws IOException {
            Object o = invoke(getChild, vfsClass, url);
            return o == null ? null : new JbossVirtualFile(o);
        }

    }

}
