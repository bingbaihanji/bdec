package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.AccessFlags;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.RecordComponentEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 类文件读取器.
 *
 * <p>负责解析 Java 类文件({@code .class} 文件)的顶层结构,
 * 输出完整的 {@link ClassFileModel} 数据结构.
 *
 * <p>解析流程遵循 JVM 类文件格式规范:
 * <ol>
 *   <li>校验魔数({@code 0xCAFEBABE})</li>
 *   <li>读取版本号</li>
 *   <li>解析常量池</li>
 *   <li>读取访问标志,类名,父类名,接口列表</li>
 *   <li>解析字段与方法</li>
 *   <li>解析类级属性(签名,引导方法,记录,密封类,内部类)</li>
 * </ol>
 */
public final class ClassFileReader {

    /** 类文件魔数:{@code 0xCAFEBABE},标识有效的 Java 类文件. */
    private static final int MAGIC = 0xCAFEBABE;

    /** 常量池解析器,负责解析类文件常量池部分. */
    private final ConstantPoolParser cpParser = new ConstantPoolParser();

    /** 结构解析器,负责解析字段和方法结构. */
    private final StructureParser structParser = new StructureParser();

    /** 注解解析器,负责解析类级注解与类型注解. */
    private final AnnotationParser annotationParser = new AnnotationParser();

    /**
     * 解析 {@code BootstrapMethods} 类属性.
     *
     * <p>该属性包含引导方法表,每个引导方法由一个方法句柄引用和
     * 若干静态参数组成,用于支持 {@code invokedynamic} 指令.
     *
     * @param in   当前属性数据流
     * @param pool 已解析的常量池
     * @return 引导方法条目列表
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    private List<BootstrapMethodEntry> parseBootstrapMethods(DataInputStream in,
                                                             ConstantPoolEntry[] pool)
            throws IOException {
        int count = in.readUnsignedShort();
        List<BootstrapMethodEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int methodRef = in.readUnsignedShort();
            int argCount = in.readUnsignedShort();
            List<Integer> arguments = new ArrayList<>(argCount);
            for (int j = 0; j < argCount; j++) {
                arguments.add(in.readUnsignedShort());
            }
            entries.add(new BootstrapMethodEntry(methodRef, arguments));
        }
        return entries;
    }

    /**
     * 解析 {@code Record} 类属性(Java 16+ 引入).
     *
     * @param in   当前属性数据流
     * @param pool 已解析的常量池
     * @return 记录组件条目列表
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    private List<RecordComponentEntry> parseRecordComponents(DataInputStream in,
                                                             ConstantPoolEntry[] pool)
            throws IOException {
        int count = in.readUnsignedShort();
        List<RecordComponentEntry> components = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nameIdx = in.readUnsignedShort();
            int descIdx = in.readUnsignedShort();
            String name = ConstantPoolParser.utf8(pool, nameIdx);
            String descriptor = ConstantPoolParser.utf8(pool, descIdx);
            // 跳过组件属性(如泛型签名等)
            int compAttrCount = in.readUnsignedShort();
            for (int j = 0; j < compAttrCount; j++) {
                in.readUnsignedShort(); // 属性名索引
                int attrLen = in.readInt();
                in.skipBytes(attrLen);
            }
            components.add(new RecordComponentEntry(name, descriptor));
        }
        return components;
    }

    /**
     * 解析 {@code PermittedSubclasses} 类属性(Java 17+ 引入).
     *
     * <p>该属性列出密封类允许的直接子类.
     *
     * @param in   当前属性数据流
     * @param pool 已解析的常量池
     * @return 允许的子类内部名称列表
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    private List<String> parsePermittedSubclasses(DataInputStream in,
                                                  ConstantPoolEntry[] pool)
            throws IOException {
        int count = in.readUnsignedShort();
        List<String> classes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int classIdx = in.readUnsignedShort();
            classes.add(ConstantPoolParser.className(pool, classIdx));
        }
        return classes;
    }

    /**
     * 解析 {@code InnerClasses} 属性.
     *
     * <p>该属性描述类文件中所有直接引用的内部类及其与外部类的关系.
     *
     * @param in   当前属性数据流
     * @param pool 已解析的常量池
     * @return 内部类条目列表
     * @throws IOException 如果读取数据流时发生 I/O 错误
     */
    private List<InnerClassEntry> parseInnerClasses(DataInputStream in,
                                                    ConstantPoolEntry[] pool)
            throws IOException {
        int count = in.readUnsignedShort();
        List<InnerClassEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int innerClassIdx = in.readUnsignedShort();
            int outerClassIdx = in.readUnsignedShort();
            int innerNameIdx = in.readUnsignedShort();
            int innerAccess = in.readUnsignedShort();
            // 索引为 0 表示该项不存在
            String innerClass = innerClassIdx != 0
                    ? ConstantPoolParser.className(pool, innerClassIdx) : null;
            String outerClass = outerClassIdx != 0
                    ? ConstantPoolParser.className(pool, outerClassIdx) : null;
            String innerName = innerNameIdx != 0
                    ? ConstantPoolParser.utf8(pool, innerNameIdx) : null;
            entries.add(new InnerClassEntry(innerClass, outerClass, innerName, innerAccess));
        }
        return entries;
    }

    /**
     * 解析 {@code Module} 类属性(JVMS 4.7.25,module-info.class).
     *
     * <p>该属性携带模块声明所需的全部信息:模块名与标志,版本,
     * requires/exports/opens/uses/provides 子句.</p>
     */
    private com.bingbaihanji.bdec.bytecode.model.ModuleInfo parseModule(DataInputStream in,
                                                                        ConstantPoolEntry[] pool) throws IOException {
        int nameIdx = in.readUnsignedShort();
        String name = ConstantPoolParser.moduleName(pool, nameIdx);
        int flags = in.readUnsignedShort();
        int versionIdx = in.readUnsignedShort();
        String version = versionIdx != 0 ? ConstantPoolParser.utf8(pool, versionIdx) : null;

        // requires 表(依赖模块)
        int reqCount = in.readUnsignedShort();
        List<com.bingbaihanji.bdec.bytecode.model.ModuleInfo.RequiresEntry> requires
                = new ArrayList<>(reqCount);
        for (int i = 0; i < reqCount; i++) {
            int modIdx = in.readUnsignedShort();
            int reqFlags = in.readUnsignedShort();
            int verIdx = in.readUnsignedShort();
            requires.add(new com.bingbaihanji.bdec.bytecode.model.ModuleInfo.RequiresEntry(
                    ConstantPoolParser.moduleName(pool, modIdx), reqFlags,
                    verIdx != 0 ? ConstantPoolParser.utf8(pool, verIdx) : null));
        }

        // exports 表(导出的包及其目标模块)
        int expCount = in.readUnsignedShort();
        List<com.bingbaihanji.bdec.bytecode.model.ModuleInfo.ExportsEntry> exports
                = new ArrayList<>(expCount);
        for (int i = 0; i < expCount; i++) {
            int pkgIdx = in.readUnsignedShort();
            int expFlags = in.readUnsignedShort();
            int toCount = in.readUnsignedShort();
            List<String> to = new ArrayList<>(toCount);
            for (int j = 0; j < toCount; j++) {
                to.add(ConstantPoolParser.moduleName(pool, in.readUnsignedShort()));
            }
            exports.add(new com.bingbaihanji.bdec.bytecode.model.ModuleInfo.ExportsEntry(
                    ConstantPoolParser.packageName(pool, pkgIdx).replace('/', '.'),
                    expFlags, to));
        }

        // opens 表(开放的包及其目标模块)
        int opensCount = in.readUnsignedShort();
        List<com.bingbaihanji.bdec.bytecode.model.ModuleInfo.OpensEntry> opens
                = new ArrayList<>(opensCount);
        for (int i = 0; i < opensCount; i++) {
            int pkgIdx = in.readUnsignedShort();
            int openFlags = in.readUnsignedShort();
            int toCount = in.readUnsignedShort();
            List<String> to = new ArrayList<>(toCount);
            for (int j = 0; j < toCount; j++) {
                to.add(ConstantPoolParser.moduleName(pool, in.readUnsignedShort()));
            }
            opens.add(new com.bingbaihanji.bdec.bytecode.model.ModuleInfo.OpensEntry(
                    ConstantPoolParser.packageName(pool, pkgIdx).replace('/', '.'),
                    openFlags, to));
        }

        // uses 表(消费的服务接口)
        int usesCount = in.readUnsignedShort();
        List<String> uses = new ArrayList<>(usesCount);
        for (int i = 0; i < usesCount; i++) {
            uses.add(ConstantPoolParser.className(pool, in.readUnsignedShort())
                    .replace('/', '.'));
        }

        // provides 表(服务实现)
        int provCount = in.readUnsignedShort();
        List<com.bingbaihanji.bdec.bytecode.model.ModuleInfo.ProvidesEntry> provides
                = new ArrayList<>(provCount);
        for (int i = 0; i < provCount; i++) {
            String service = ConstantPoolParser.className(pool, in.readUnsignedShort())
                    .replace('/', '.');
            int withCount = in.readUnsignedShort();
            List<String> with = new ArrayList<>(withCount);
            for (int j = 0; j < withCount; j++) {
                with.add(ConstantPoolParser.className(pool, in.readUnsignedShort())
                        .replace('/', '.'));
            }
            provides.add(new com.bingbaihanji.bdec.bytecode.model.ModuleInfo.ProvidesEntry(
                    service, with));
        }

        return new com.bingbaihanji.bdec.bytecode.model.ModuleInfo(
                name, flags, version, requires, exports, opens, uses, provides);
    }

    /**
     * 读取一个类文件的完整内容并构造 {@link ClassFileModel}.
     *
     * <p>这是类文件解析的主入口方法,按照 JVM 类文件格式顺序读取
     * 所有结构,最终组装成不可变的模型对象.
     *
     * @param internalName 类的内部名称(以斜杠分隔)
     * @param bytes        类文件的原始字节数组
     * @return 完整的类文件模型
     * @throws IOException 如果类文件格式错误或读取失败
     */
    public ClassFileModel read(String internalName, byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        // 校验魔数
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Not a class file: bad magic 0x"
                    + Integer.toHexString(magic));
        }

        // 解析版本号
        int minor = in.readUnsignedShort();
        int major = in.readUnsignedShort();
        // 解析常量池
        ConstantPoolEntry[] pool = cpParser.parse(in);
        // 读取访问标志
        int accessFlags = in.readUnsignedShort();

        // 读取当前类名.
        // module-info.class 的 this_class 指向 CONSTANT_Module 而非 CONSTANT_Class
        // (ACC_MODULE = 0x8000).
        int thisClassIdx = in.readUnsignedShort();
        String thisClassName = (accessFlags & AccessFlags.ACC_MODULE) != 0
                ? ConstantPoolParser.moduleName(pool, thisClassIdx)
                : ConstantPoolParser.className(pool, thisClassIdx);

        // 读取父类名(索引为 0 表示当前类是 java.lang.Object)
        int superClassIdx = in.readUnsignedShort();
        String superName = superClassIdx == 0 ? null
                : ConstantPoolParser.className(pool, superClassIdx);

        // 读取接口列表
        int ifaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < ifaceCount; i++) {
            int idx = in.readUnsignedShort();
            interfaces.add(ConstantPoolParser.className(pool, idx));
        }

        // 解析字段
        int fieldCount = in.readUnsignedShort();
        var fields = structParser.parseFields(in, pool, fieldCount);

        // 解析方法
        int methodCount = in.readUnsignedShort();
        var methods = structParser.parseMethods(in, pool, methodCount);

        // 解析类级属性
        int attrCount = in.readUnsignedShort();
        String signature = "";
        List<BootstrapMethodEntry> bootstrapMethods = Collections.emptyList();
        List<RecordComponentEntry> recordComponents = Collections.emptyList();
        List<String> permittedSubclasses = Collections.emptyList();
        List<InnerClassEntry> innerClasses = Collections.emptyList();
        List<com.bingbaihanji.bdec.bytecode.model.AnnotationEntry> classAnnotations
                = Collections.emptyList();
        com.bingbaihanji.bdec.bytecode.model.ModuleInfo moduleInfo = null;
        java.util.List<com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry>
                classTypeAnnotations = Collections.emptyList();
        for (int i = 0; i < attrCount; i++) {
            int attrNameIdx = in.readUnsignedShort();
            int attrLen = in.readInt();
            String attrName = ConstantPoolParser.utf8(pool, attrNameIdx);
            // 畸形类文件中属性名索引可能无效(utf8 返回 null),跳过该属性.
            if (attrName == null) {
                in.skipBytes(attrLen);
                continue;
            }
            switch (attrName) {
                case "Signature" -> {
                    int sigIdx = in.readUnsignedShort();
                    signature = ConstantPoolParser.utf8(pool, sigIdx);
                }
                case "BootstrapMethods" -> {
                    bootstrapMethods = parseBootstrapMethods(in, pool);
                }
                case "Record" -> {
                    recordComponents = parseRecordComponents(in, pool);
                }
                case "PermittedSubclasses" -> {
                    permittedSubclasses = parsePermittedSubclasses(in, pool);
                }
                case "InnerClasses" -> {
                    innerClasses = parseInnerClasses(in, pool);
                }
                case "RuntimeVisibleAnnotations" -> {
                    classAnnotations = annotationParser.parseAnnotations(in, pool);
                }
                case "RuntimeVisibleTypeAnnotations" -> {
                    classTypeAnnotations = annotationParser.parseTypeAnnotations(in, pool);
                }
                case "Module" -> {
                    moduleInfo = parseModule(in, pool);
                }
                default -> in.skipBytes(attrLen);
            }
        }

        return new ClassFileModel(major, minor, accessFlags,
                thisClassName, superName, interfaces, fields, methods, pool, signature,
                bootstrapMethods, recordComponents, permittedSubclasses, innerClasses,
                classAnnotations, moduleInfo, classTypeAnnotations);
    }
}
