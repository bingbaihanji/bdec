package com.bingbaihanji.bdec.decompiler.utils.java;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 源码编译工具类,封装 {@link JavaCompiler} 相关功能
 * <p>
 * 支持源码文件编译,内存字符串代码动态编译(In-Memory Compilation)以及字节码直接加载.
 * </p>
 *
 * @author bingbaihanji
 * @since 2026-06-10
 */
public class JavaCompilerUtil {

    /** 系统 Java 编译器(在极少数没有 JDK 编译组件的环境下可能为 null) */
    public static final JavaCompiler SYSTEM_COMPILER = ToolProvider.getSystemJavaCompiler();

//    private static final Logger log = LoggerFactory.getLogger(JavaCompilerUtil.class);

    private JavaCompilerUtil() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 检查当前运行环境的编译器是否可用
     *
     * @return true 表示编译器可用,false 表示缺失 JDK 编译工具组件
     */
    public static boolean isCompilerAvailable() {
        return SYSTEM_COMPILER != null;
    }

    // ==========================================
    // 1. 基于文件的传统编译 API
    // ==========================================

    /**
     * 编译指定的源码文件
     *
     * @param sourceFiles 源码文件路径
     * @return true 表示编译成功,false 表示编译失败或编译器不可用
     */
    public static boolean compile(String... sourceFiles) {
        if (!isCompilerAvailable() || sourceFiles == null || sourceFiles.length == 0) {
//            log.warn("编译器不可用或未传入待编译源码文件");
            return false;
        }
        return 0 == SYSTEM_COMPILER.run(null, null, null, sourceFiles);
    }

    /**
     * 获取 {@link StandardJavaFileManager}
     *
     * @return {@link StandardJavaFileManager}
     */
    public static StandardJavaFileManager getFileManager() {
        return getFileManager(null);
    }

    /**
     * 获取 {@link StandardJavaFileManager}
     *
     * @param diagnosticListener 异常/诊断收集器
     * @return {@link StandardJavaFileManager}
     */
    public static StandardJavaFileManager getFileManager(DiagnosticListener<? super JavaFileObject> diagnosticListener) {
        ensureCompilerAvailable();
        return SYSTEM_COMPILER.getStandardFileManager(diagnosticListener, Locale.getDefault(), StandardCharsets.UTF_8);
    }

    /**
     * 创建编译任务
     *
     * @param fileManager        {@link JavaFileManager}
     * @param diagnosticListener 诊断监听
     * @param options            编译选项(如 -classpath, -d 等)
     * @param compilationUnits   编译单元
     * @return {@link JavaCompiler.CompilationTask}
     */
    public static JavaCompiler.CompilationTask getTask(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Iterable<String> options,
            Iterable<? extends JavaFileObject> compilationUnits) {
        ensureCompilerAvailable();
        return SYSTEM_COMPILER.getTask(null, fileManager, diagnosticListener, options, null, compilationUnits);
    }

    // ==========================================
    // 2. 内存源码动态编译 API (In-Memory Compilation)
    // ==========================================

    /**
     * 动态编译内存中的字符串源码,并返回生成的类字节码 Map
     *
     * @param className 全类名(如 com.example.Foo)
     * @param source    Java 源码字符串
     * @return 编译产出的全类名与字节码映射关系(包含内部类)
     */
    public static Map<String, byte[]> compileToBytes(String className, String source) {
        return compileToBytes(Collections.singletonMap(className, source), Collections.emptyList());
    }

    /**
     * 批量动态编译内存源码字符串
     *
     * @param sourceMap 类名与源码内容的映射表 (className -> sourceCode)
     * @param options   编译选项(如 -classpath 等,可为 null)
     * @return 编译产出的全类名与字节码映射关系 Map(className -> byte[])
     */
    public static Map<String, byte[]> compileToBytes(Map<String, String> sourceMap, List<String> options) {
        ensureCompilerAvailable();
        Objects.requireNonNull(sourceMap, "sourceMap 不能为 null");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = getFileManager(diagnostics);
        MemoryBytecodeJavaFileManager memoryFileManager = new MemoryBytecodeJavaFileManager(stdFileManager);

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (Map.Entry<String, String> entry : sourceMap.entrySet()) {
            compilationUnits.add(new StringJavaFileObject(entry.getKey(), entry.getValue()));
        }

        JavaCompiler.CompilationTask task = SYSTEM_COMPILER.getTask(
                null, memoryFileManager, diagnostics, options, null, compilationUnits);

        boolean success = Boolean.TRUE.equals(task.call());
        if (!success) {
            String errorMsg = diagnostics.getDiagnostics().stream()
                    .map(Diagnostic::toString)
                    .collect(Collectors.joining("\n"));
//            log.error("动态编译源码失败:\n{}", errorMsg);
            throw new IllegalStateException("动态编译 Java 源码失败:\n" + errorMsg);
        }

        return memoryFileManager.getBytecodeMap();
    }

    /**
     * 动态编译单个 Java 源码并直接加载为 {@link Class}
     *
     * @param className   全类名
     * @param source      Java 源码字符串
     * @param classLoader 指定用于加载生成的类加载器(可为 null,默认使用 ContextClassLoader)
     * @return 编译并加载后的 Class 对象
     */
    public static Class<?> compileAndLoad(String className, String source, ClassLoader classLoader) {
        Map<String, byte[]> byteCodeMap = compileToBytes(className, source);
        byte[] byteCode = byteCodeMap.get(className);
        if (byteCode == null) {
            throw new IllegalStateException("未能为类 [" + className + "] 生成有效的字节码");
        }

        ClassLoader parentLoader = (classLoader != null) ? classLoader : Thread.currentThread().getContextClassLoader();
        MemoryClassLoader memoryClassLoader = new MemoryClassLoader(byteCodeMap, parentLoader);
        try {
            return memoryClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("加载动态编译类 [" + className + "] 失败", e);
        }
    }

    private static void ensureCompilerAvailable() {
        if (!isCompilerAvailable()) {
            throw new UnsupportedOperationException("当前 JRE/JDK 环境中缺乏 JavaCompiler,请确保使用的是标准 JDK 环境而非精简版 JRE");
        }
    }

    // ==========================================
    // 3. 内存编译辅助内部类
    // ==========================================

    static void main() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {

        String javaSourceCode = """
                                package com.example;
                                
                                public class HelloService {
                                     public String sayHello(String name) {
                                        return "Hello, " + name;
                                     }
                                }
                                """;

        // 编译并加载
        Class<?> clazz = JavaCompilerUtil.compileAndLoad("com.example.HelloService", javaSourceCode, null);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        // 反射调用
        Method method = clazz.getMethod("sayHello", String.class);
        String result = (String) method.invoke(instance, "World");
        IO.println(result); // 输出: Hello, World
    }

    /**
     * 内存 Java 源码文件对象
     */
    private static class StringJavaFileObject extends SimpleJavaFileObject {

        private final String content;

        public StringJavaFileObject(String className, String content) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    /**
     * 内存字节码输出文件对象
     */
    private static class MemoryBytecodeJavaFileObject extends SimpleJavaFileObject {

        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        public MemoryBytecodeJavaFileObject(String className) {
            super(URI.create("byte:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return outputStream;
        }

        public byte[] getByteCode() {
            return outputStream.toByteArray();
        }
    }

    /**
     * 拦截编译器输出的内存 FileManager
     */
    private static class MemoryBytecodeJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {

        private final Map<String, MemoryBytecodeJavaFileObject> bytecodeObjects = new HashMap<>();

        protected MemoryBytecodeJavaFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            MemoryBytecodeJavaFileObject fileObject = new MemoryBytecodeJavaFileObject(className);
            bytecodeObjects.put(className, fileObject);
            return fileObject;
        }

        public Map<String, byte[]> getBytecodeMap() {
            Map<String, byte[]> map = new HashMap<>();
            for (Map.Entry<String, MemoryBytecodeJavaFileObject> entry : bytecodeObjects.entrySet()) {
                map.put(entry.getKey(), entry.getValue().getByteCode());
            }
            return map;
        }
    }

    /**
     * 用于加载内存字节码的 ClassLoader
     */
    private static class MemoryClassLoader extends ClassLoader {

        private final Map<String, byte[]> bytecodeMap;

        public MemoryClassLoader(Map<String, byte[]> bytecodeMap, ClassLoader parent) {
            super(parent);
            this.bytecodeMap = bytecodeMap;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = bytecodeMap.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }
}
