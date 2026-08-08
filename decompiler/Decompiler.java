package com.bingbaihanji.bdec.decompiler;

import com.bingbaihanji.bdec.BdecConfig;
import com.bingbaihanji.bdec.BdecResult;
import com.bingbaihanji.bdec.DecompileContext;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * 反编译引擎统一接口。
 * <p>
 * 所有引擎实现必须保证<b>线程安全（Thread-Safe）</b>或<b>无状态（Stateless）</b>。
 *
 * @author bingbaihanji
 * @date 2026-06-17
 */
public interface Decompiler extends AutoCloseable {

    /**
     * 获取引擎标识/名称
     *
     * @return 引擎名称，例如 "bdec-core"
     */
    default String getName() {
        return "bdec";
    }

    /**
     * 获取引擎版本号
     *
     * @return 语义化版本号
     */
    default String getVersion() {
        return "1.0.0";
    }

    /**
     * 引擎初始化（在引擎加载或配置变更时调用）
     *
     * @param globalOptions 引擎全局配置选项
     */
    default void initialize(Map<String, String> globalOptions) {
    }

    /**
     * 按字节码反编译单个 Class
     *
     * @param internalName 类内部名称（例如: "java/lang/String" 或 "com/example/TestClass$InnerClass"）
     * @param classBytes   字节码数据
     * @return 反编译结果对象
     */
    default BdecResult decompile(String internalName, byte[] classBytes) {
        return decompile(internalName, classBytes, DecompileContext.empty(BdecConfig.defaults()));
    }

    /**
     * 带上下文的反编译接口（核心 API）
     *
     * @param internalName 类内部名称
     * @param classBytes   目标字节码数据
     * @param context      反编译上下文（包含依赖加载器、自定义选项等）
     * @return 反编译结果对象
     */
    BdecResult decompile(String internalName, byte[] classBytes, DecompileContext context);

    /**
     * 便捷方法：直接从本地文件系统反编译 Class 文件
     *
     * @param classFile Class 文件路径
     * @param context   反编译上下文
     * @return 反编译结果对象
     */
    default BdecResult decompile(Path classFile, DecompileContext context) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(classFile);
            String internalName = deriveInternalName(classFile);
            return decompile(internalName, bytes, context);
        } catch (Exception e) {
            return BdecResult.error(e);
        }
    }

    /** Derive internal name from a class file path, walking up for package directories. */
    private String deriveInternalName(Path classFile) {
        Path absolute = classFile.toAbsolutePath().normalize();
        String fileName = absolute.getFileName().toString();
        String baseName = fileName.endsWith(".class")
                ? fileName.substring(0, fileName.length() - 6) : fileName;

        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(baseName);
        Path parent = absolute.getParent();
        while (parent != null) {
            String dirName = parent.getFileName().toString();
            if (dirName.isEmpty() || !isJavaIdentifier(dirName)) {
                break;
            }
            parts.addFirst(dirName);
            parent = parent.getParent();
        }
        return String.join("/", parts);
    }

    private boolean isJavaIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取引擎支持的配置项及默认值
     *
     * @return Key-Value 映射表
     */
    default Map<String, String> getDefaultOptions() {
        return Collections.emptyMap();
    }

    /**
     * 资源清理（实现 AutoCloseable 接口，方便在 Try-with-resources 中安全销毁）
     */
    @Override
    default void close() {
        cleanup();
    }

    /**
     * 引擎清理逻辑
     */
    default void cleanup() {
    }
}
