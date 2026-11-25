package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单次反编译操作上下文,携带反编译过程中所需的辅助数据和回调.
 *
 * <p>上下文包含以下关键信息:</p>
 * <ul>
 *   <li>类型化配置({@link BdecConfig})</li>
 *   <li>类字节码加载器回调,用于解析依赖类型</li>
 *   <li>BootstrapMethod 数据,供 LambdaRewriter,MethodRefRewriter 等重写规则使用</li>
 *   <li>已解析的 ClassFileModel,供需要字节码级别访问的重写规则(如 EnumRewriter)使用</li>
 * </ul>
 */
public class DecompileContext {

    /** 反编译配置 */
    private final BdecConfig config;

    /** 类字节码加载回调函数,通过内部名称加载依赖类的字节码 */
    private final Function<String, byte[]> classByteLoader;

    /** BootstrapMethod 表,用于 Lambda 表达式和方法引用的解析 */
    private final List<BootstrapMethodEntry> bootstrapMethods;

    /** 已解析的 class 文件模型,供重写规则进行字节码级别的分析 */
    private final ClassFileModel classFile;

    /** 警告信息接收器,接收反编译过程中无法表示为诊断事件的非致命问题描述 */
    private final Consumer<String> warningSink;

    /**
     * 构造反编译上下文(不含 BootstrapMethod 和 ClassFileModel,警告接收器为 no-op).
     *
     * @param config          反编译配置
     * @param classByteLoader 类字节码加载回调
     */
    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader) {
        this(config, classByteLoader, Collections.emptyList(), null, message -> {});
    }

    /**
     * 构造反编译上下文(含 BootstrapMethod,不含 ClassFileModel,警告接收器为 no-op).
     *
     * @param config           反编译配置
     * @param classByteLoader  类字节码加载回调
     * @param bootstrapMethods BootstrapMethod 表
     */
    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader,
                            List<BootstrapMethodEntry> bootstrapMethods) {
        this(config, classByteLoader, bootstrapMethods, null, message -> {});
    }

    /**
     * 构造完整的反编译上下文(警告接收器为 no-op).
     *
     * @param config           反编译配置
     * @param classByteLoader  类字节码加载回调
     * @param bootstrapMethods BootstrapMethod 表
     * @param classFile        已解析的 class 文件模型,可为 null
     */
    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader,
                            List<BootstrapMethodEntry> bootstrapMethods,
                            ClassFileModel classFile) {
        this(config, classByteLoader, bootstrapMethods, classFile, message -> {});
    }

    /**
     * 构造完整的反编译上下文(含警告接收器).
     *
     * @param config           反编译配置
     * @param classByteLoader  类字节码加载回调
     * @param bootstrapMethods BootstrapMethod 表
     * @param classFile        已解析的 class 文件模型,可为 null
     * @param warningSink      警告信息接收器,可为 null(此时丢弃警告)
     */
    public DecompileContext(BdecConfig config, Function<String, byte[]> classByteLoader,
                            List<BootstrapMethodEntry> bootstrapMethods,
                            ClassFileModel classFile, Consumer<String> warningSink) {
        this.config = config;
        this.classByteLoader = classByteLoader;
        this.bootstrapMethods = Collections.unmodifiableList(bootstrapMethods);
        this.classFile = classFile;
        this.warningSink = warningSink != null ? warningSink : message -> {};
    }

    /**
     * 创建适用于简单单类反编译的空上下文.
     *
     * @param config 反编译配置
     * @return 不含额外数据的空上下文实例(警告接收器为 no-op)
     */
    public static DecompileContext empty(BdecConfig config) {
        return new DecompileContext(config, null, Collections.emptyList(), null, message -> {});
    }

    /** 获取反编译配置 */
    public BdecConfig config() {return config;}

    /**
     * 根据内部名称加载依赖类的字节码.
     *
     * @param internalName 类的内部名称(如 {@code com/example/Foo$Bar})
     * @return 对应类的字节数组,若加载失败或加载器未设置则返回 null
     */
    public byte[] loadClassBytes(String internalName) {
        return classByteLoader != null ? classByteLoader.apply(internalName) : null;
    }

    /**
     * 报告一条非致命警告.
     *
     * <p>反编译器在兜底处理(如不可归约 CFG 回退,未声明变量自动补声明)时
     * 通过该回调说明降级原因.消息为 null 时忽略.</p>
     *
     * @param message 单行人类可读警告消息
     */
    public void reportWarning(String message) {
        if (message != null) {
            warningSink.accept(message);
        }
    }

    /**
     * 获取 class 文件中的 BootstrapMethod 表.
     *
     * <p>此数据用于 Lambda 表达式和方法引用的解析.</p>
     *
     * @return BootstrapMethod 条目列表(不可修改)
     */
    public List<BootstrapMethodEntry> bootstrapMethods() {return bootstrapMethods;}

    /**
     * 获取已解析的 class 文件模型.
     *
     * <p>此数据供重写规则进行字节码级别的分析使用.</p>
     *
     * @return 已解析的 ClassFileModel,可能为 null
     */
    public ClassFileModel classFile() {return classFile;}
}
