package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.FieldModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AST(抽象语法树)构建器.
 * <p>
 * 负责将字节码模型({@link ClassFileModel})和结构化方法列表({@link StructuredMethod})
 * 转换为AST表示({@link CompilationUnit}).主要功能包括:
 * </p>
 * <ul>
 *   <li>从class文件元数据中提取类型声明(类名,父类,接口,泛型参数等)</li>
 *   <li>构建字段声明(包括常量初始化器和泛型字段类型)</li>
 *   <li>构建方法声明(包括参数名,方法级泛型参数)</li>
 *   <li>收集并自动生成import语句(包括方法体中引用的类型)</li>
 * </ul>
 */
public class AstBuilder {

    /**
     * 从内部名称提取简单类名,优先使用内部类表中的友好名称.
     * 例如,对于内部名称 {@code com/example/Outer$Inner},若内部类表中有
     * 名为 {@code "Inner"} 的条目,则返回 {@code "Inner"}.
     *
     * @param internal     类的内部名称(如 "com/example/Outer$Inner")
     * @param innerClasses 内部类条目列表
     * @return 提取的简单类名
     */
    private static String simpleName(String internal, List<com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry> innerClasses) {
        int idx = internal.lastIndexOf('/');
        String raw = idx >= 0 ? internal.substring(idx + 1) : internal;
        // 查找内部类表中是否有友好名称
        for (var ice : innerClasses) {
            if (internal.equals(ice.innerClass()) && ice.simpleName() != null) {
                return ice.simpleName();
            }
        }
        return raw;
    }

    /**
     * 从内部名称提取简单类名(无内部类信息).
     *
     * @param internal 类的内部名称(如 "com/example/MyClass")
     * @return 提取的简单类名
     */
    private static String simpleName(String internal) {
        int idx = internal.lastIndexOf('/');
        return idx >= 0 ? internal.substring(idx + 1) : internal;
    }

    /** 检查简单类名是否为匿名类引用(如 TestClass2$1, Foo$2LocalClass).
     *  匿名类在 $ 后紧跟数字,在 Java 源码中不可作为类型名引用. */
    private static boolean isAnonymousClassRef(String simpleName) {
        int idx = simpleName.lastIndexOf('$');
        if (idx >= 0 && idx + 1 < simpleName.length()) {
            char c = simpleName.charAt(idx + 1);
            return c >= '0' && c <= '9';
        }
        return false;
    }

    /**
     * 从方法体的IR指令中收集类型引用,用于生成import语句.
     * 扫描静态方法调用的DECLARING_CLASS注解,new表达式,new数组和instanceof
     * 指令中引用的类型.
     *
     * @param sm        结构化方法
     * @param imports   待填充的import集合
     * @param thisClass 当前类的简单名称(避免自引用)
     */
    private void collectBodyImports(StructuredMethod sm, Set<String> imports, String thisClass) {
        if (sm.ir() == null || sm.ir().instructions() == null) {
            return; // 抽象方法或本地方法没有IR
        }
        for (var insn : sm.ir().instructions()) {
            // 静态方法调用:检查DECLARING_CLASS注解
            for (var ann : insn.annotations()) {
                if (ann.is(com.bingbaihanji.bdec.semantic.SemanticTag.DECLARING_CLASS)) {
                    String declClass = ann.getString(
                            com.bingbaihanji.bdec.semantic.SemanticAnnotation.KEY_DECLARING_CLASS);
                    if (declClass != null) {
                        collectImport(com.bingbaihanji.bdec.type.JavaType.classType(declClass),
                                imports, thisClass);
                    }
                }
            }
            // NEW指令:从resultType提取类型
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.NEW) {
                collectImport(insn.resultType(), imports, thisClass);
            }
            // NEW_ARRAY指令:提取数组元素类型
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.NEW_ARRAY) {
                collectImport(insn.resultType(), imports, thisClass);
            }
            // INSTANCE_OF指令:从nameHint提取目标类型
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.INSTANCE_OF
                    && insn.nameHint() != null) {
                collectImport(com.bingbaihanji.bdec.type.JavaType.classType(insn.nameHint()),
                        imports, thisClass);
            }
            // Iterator模式:当调用iterator()/hasNext()/next()时,添加Iterator导入
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.INVOKE
                    && insn.nameHint() != null) {
                String mName = insn.nameHint();
                if ("iterator".equals(mName) || "hasNext".equals(mName)
                        || "next".equals(mName)) {
                    collectImport(com.bingbaihanji.bdec.type.JavaType.classType(
                            "java/util/Iterator"), imports, thisClass);
                }
                // Map.entry() 调用需要 Map.Entry 的导入
                if ("entry".equals(mName) && insn.resultType() != null
                        && insn.resultType().internalName() != null
                        && insn.resultType().internalName().contains("Map$Entry")) {
                    collectImport(com.bingbaihanji.bdec.type.JavaType.classType(
                            "java/util/Map$Entry"), imports, thisClass);
                }
            }
        }
    }

    /**
     * 构建编译单元.
     * <p>
     * 将class文件模型和结构化方法列表组装为完整的AST编译单元,
     * 包括字段声明,方法声明,类型声明和import语句的生成.
     * </p>
     *
     * @param classFile class文件模型
     * @param methods   结构化方法列表
     * @param ctx       反编译上下文
     * @return 构建完成的编译单元
     */
    public CompilationUnit build(ClassFileModel classFile, List<StructuredMethod> methods,
                                 @SuppressWarnings("unused") DecompileContext ctx) {
        List<AstNode> members = new ArrayList<>();
        Set<String> imports = new LinkedHashSet<>();

        // 获取简单类名
        String simpleName = simpleName(classFile.internalName(), classFile.innerClasses());

        // 检测当前类是否为非静态内部类(在过滤合成字段之前检查).
        // 若为内部类,在构建构造函数时需去除编译器合成的外围实例引用参数.
        boolean isNonStaticInner = classFile.fields().stream()
                .anyMatch(f -> (f.accessFlags() & 0x1000) != 0
                        && f.name().startsWith("this$"));

        // 缓存类的泛型类型参数,用于后续方法签名解析,避免重复解析
        List<String> classTypeParams = SignatureParser.extractTypeParams(
                classFile.signature());

        // 构建字段声明
        for (FieldModel field : classFile.fields()) {
            // 跳过 JVM 合成的 this$X 字段(非静态内部类的外围实例引用),
            // 也跳过其他 ACC_SYNTHETIC 字段(编译器合成,不应出现在源码中)
            if ((field.accessFlags() & 0x1000) != 0) {
                continue;
            }
            Expression init = parseFieldInitializer(field);
            // 若字段有泛型签名,则解析泛型类型参数以替代原始类型
            JavaType displayType = field.type();
            if (field.signature() != null && !field.signature().isEmpty()) {
                JavaType parsed = SignatureParser.parseGenericType(field.signature());
                if (parsed != null) {
                    displayType = parsed;
                }
            }
            // $assertionsDisabled是JVM合成的断言标志字段,给它一个默认值false,
            // 因为其静态初始化器赋值已被移除(JVM内部机制)
            if (init == null && "$assertionsDisabled".equals(field.name())) {
                init = new com.bingbaihanji.bdec.ast.expr.LitExpr(false, JavaType.BOOLEAN);
            }
            FieldDeclaration fd = new FieldDeclaration(
                    field.accessFlags(), field.name(), displayType, init);
            members.add(fd);
            collectImport(field.type(), imports, simpleName);
        }

        // 构建方法声明
        for (StructuredMethod sm : methods) {
            MethodModel method = sm.method();
            String[] paramNames = buildParameterNames(method);
            JavaType[] paramTypes = method.parameterTypes();
            String methodName = resolveMethodName(method.name(), simpleName,
                    classFile.accessFlags());

            // 对于非静态内部类的构造函数,去除编译器合成的首个参数
            //(外围 this$0 引用).该参数在 Java 源码中并不存在.
            if (isNonStaticInner && "<init>".equals(method.name())
                    && paramNames.length > 0 && paramTypes.length > 0) {
                paramNames = java.util.Arrays.copyOfRange(paramNames, 1, paramNames.length);
                paramTypes = java.util.Arrays.copyOfRange(paramTypes, 1, paramTypes.length);
            }

            // 从泛型签名中提取方法级类型参数
            List<String> methodTypeParams = method.signature() != null
                    && !method.signature().isEmpty()
                    ? SignatureParser.extractMethodTypeParams(method.signature())
                    : List.of();

            // 使用泛型签名覆盖返回类型和参数类型(签名包含泛型类型变量,
            // 而描述符仅包含原始类型 Object).仅当类声明了类型参数时才应用,
            // 因为方法级类型变量名(如 <T>)与类型名字相同,无需额外处理.
            JavaType returnType = method.returnType();
            if (!classTypeParams.isEmpty()
                    && method.signature() != null && !method.signature().isEmpty()) {
                JavaType[] sigTypes = SignatureParser.parseMethodSignature(
                        method.signature());
                if (sigTypes != null && sigTypes.length == paramTypes.length + 1) {
                    // sigTypes = [param0, param1, ..., returnType]
                    // 仅当签名中的类型变量与类的类型参数匹配时才替换
                    for (int si = 0; si < paramTypes.length; si++) {
                        if (isClassTypeParam(sigTypes[si], classTypeParams)) {
                            paramTypes[si] = sigTypes[si];
                        }
                    }
                    if (isClassTypeParam(sigTypes[sigTypes.length - 1], classTypeParams)) {
                        returnType = sigTypes[sigTypes.length - 1];
                    }
                }
            }

            MethodDeclaration decl = new MethodDeclaration(
                    method.accessFlags(),
                    methodName,
                    returnType,
                    paramNames,
                    paramTypes,
                    methodTypeParams,
                    sm.body()
            );
            members.add(decl);

            // 从方法签名中收集类型引用以生成import
            collectImport(returnType, imports, simpleName);
            for (JavaType pt : paramTypes) {
                collectImport(pt, imports, simpleName);
            }

            // 从方法体中引用的类型收集import.
            // 扫描IR指令中的DECLARING_CLASS注解以发现静态调用目标类型.
            collectBodyImports(sm, imports, simpleName);
        }

        // 确定类型种类:接口,注解,枚举或普通类
        String kind = (classFile.accessFlags() & 0x0200) != 0 ? "interface"
                : (classFile.accessFlags() & 0x4000) != 0 ? "@interface"
                : (classFile.accessFlags() & 0x2000) != 0 ? "enum" : "class";

        // 解析父类名称(排除java.lang.Object的默认继承)
        String superName = classFile.superInternalName() != null
                && !"java/lang/Object".equals(classFile.superInternalName())
                ? simpleName(classFile.superInternalName()) : null;
        if (superName != null) {
            collectImport(JavaType.classType(classFile.superInternalName()), imports, simpleName);
        }

        // 解析实现的接口名称列表
        List<String> interfaceNames = new ArrayList<>();
        for (String ifName : classFile.interfaceInternalNames()) {
            String simple = simpleName(ifName);
            interfaceNames.add(simple);
            collectImport(JavaType.classType(ifName), imports, simpleName);
        }

        // 使用缓存的泛型类型参数(已在类头部预先解析)
        TypeDeclaration td = new TypeDeclaration(
                classFile.accessFlags(), simpleName, kind,
                superName, interfaceNames, classTypeParams, members);

        // 构建import列表(过滤java.lang.*和同包类型)
        List<String> importList = new ArrayList<>();
        String pkg = packageName(classFile.internalName());
        for (String imp : imports) {
            // 仅跳过java.lang直接包中的类型,不跳过子包中的类型
            // (例如java.lang.annotation.Annotation仍需显式导入)
            if (imp.startsWith("java.lang.")
                    && imp.indexOf('.', "java.lang.".length()) < 0) {
                continue; // java.lang.* 自动导入
            }
            if (!imp.contains(".")) {
                continue;
            }
            String impPkg = imp.substring(0, imp.lastIndexOf('.'));
            if (impPkg.equals(pkg)) {
                continue; // 同包类型无需导入
            }
            importList.add(imp);
        }
        java.util.Collections.sort(importList);

        // 构建内部类友好名称映射:简单内部名称(如 TestClass2$StaticNested) → 友好名称(如 StaticNested).
        // 仅限成员内部类(不以 $数字 开头),匿名/局部类保留字节码名称以确保类路径解析.
        java.util.Map<String, String> innerNames = new java.util.HashMap<>();
        for (var ice : classFile.innerClasses()) {
            if (ice.simpleName() != null && !ice.simpleName().isEmpty()
                    && ice.innerClass() != null) {
                String rawSimple = simpleName(ice.innerClass());
                // 仅对成员内部类使用友好名称(跳过匿名类如 TestClass2$1 和局部类如 TestClass2$1LocalClass)
                if (!isAnonymousClassRef(rawSimple) && !rawSimple.equals(ice.simpleName())) {
                    innerNames.put(rawSimple, ice.simpleName());
                }
            }
        }

        return new CompilationUnit(pkg, importList, List.of(td), innerNames);
    }

    /**
     * 构建方法的参数名列表.
     * 优先从局部变量表(LocalVariableTable)中获取参数名,
     * 若不可用则回退为顺序生成的"paramN"名称.
     *
     * @param method 方法模型
     * @return 参数名数组
     */
    private String[] buildParameterNames(MethodModel method) {
        int paramCount = method.parameterTypes().length;
        String[] names = new String[paramCount];
        var lvt = method.localVarNames();

        // 非静态方法中,slot 0是"this"引用,因此参数从slot 1开始
        // 静态方法中,参数从slot 0开始
        // Category-2类型(long/double)占用两个slot
        int slot = method.isStatic() ? 0 : 1;

        for (int i = 0; i < paramCount; i++) {
            // 优先尝试从LVT获取名称
            String lvtName = lvt.get(slot);
            if (lvtName != null && !lvtName.isEmpty()) {
                names[i] = lvtName;
            } else {
                names[i] = "param" + i;
            }
            // 根据参数类型跨过对应的slot数量
            JavaType pt = method.parameterTypes()[i];
            boolean cat2 = pt != null && (pt.kind() == com.bingbaihanji.bdec.type.TypeKind.LONG
                    || pt.kind() == com.bingbaihanji.bdec.type.TypeKind.DOUBLE);
            slot += cat2 ? 2 : 1;
        }
        return names;
    }

    /**
     * 解析方法名.
     * 构造器方法(&lt;init&gt;)使用类名作为方法名,
     * 静态初始化器(&lt;clinit&gt;)返回null(由其他逻辑单独处理).
     *
     * @param methodName 字节码中的原始方法名
     * @param className  类名
     * @param classFlags 类访问标志
     * @return 解析后的方法名,静态初始化器返回null
     */
    private String resolveMethodName(String methodName, String className,
                                     @SuppressWarnings("unused") int classFlags) {
        if ("<init>".equals(methodName)) {
            return className;
        }
        if ("<clinit>".equals(methodName)) {
            return null; // 静态初始化器由其他逻辑处理
        }
        return methodName;
    }

    /**
     * 从内部名称中提取包名.
     *
     * @param internalName 类的内部名称(如 "com/example/MyClass")
     * @return 包名(如 "com.example"),默认包返回空字符串
     */
    private String packageName(String internalName) {
        int idx = internalName.lastIndexOf('/');
        return idx > 0 ? internalName.substring(0, idx).replace('/', '.') : "";
    }

    /**
     * 解析字段的常量初始值,将其包装为字面量表达式.
     *
     * @param field 字段模型
     * @return 字面量表达式,若无常量值则返回null
     */
    private Expression parseFieldInitializer(FieldModel field) {
        Object cv = field.constantValue();
        if (cv == null) {
            return null;
        }
        if (cv instanceof String s) {
            return new LitExpr(s, JavaType.classType("java/lang/String"));
        }
        if (cv instanceof Number || cv instanceof Boolean || cv instanceof Character) {
            return new LitExpr(cv, field.type());
        }
        return null;
    }

    /**
     * 检查一个 JavaType 是否表示指定类类型参数列表中的类型变量.
     * 类型变量在签名中以 {@code TName;} 格式出现,由 SignatureParser
     * 解析为 {@code simpleName = 类型变量名} 的 CLASS 类型.
     */
    private static boolean isClassTypeParam(JavaType type, List<String> classTypeParams) {
        if (type == null || classTypeParams.isEmpty()) {
            return false;
        }
        // 类型变量在 SignatureParser 中被解析为 CLASS 类型,
        // 并以内部名称作为类型变量名(如 "T","U")
        String name = type.internalName();
        return name != null && classTypeParams.contains(name);
    }

    /**
     * 为指定类型收集import条目(如果满足导入条件).
     * 仅当类型为CLASS类型且来自不同包(非当前类)时才添加到import集合中.
     *
     * @param type      需要检查的Java类型
     * @param imports   待填充的import集合
     * @param thisClass 当前类的简单名称
     */
    private void collectImport(JavaType type, Set<String> imports, String thisClass) {
        if (type == null) {
            return;
        }
        if (type.kind() == com.bingbaihanji.bdec.type.TypeKind.CLASS) {
            String internalName = type.internalName();
            if (internalName != null && !simpleName(internalName).equals(thisClass)) {
                String dotted = internalName.replace('/', '.');
                // 对命名内部类将 $ 转换为 .(如 Map$Entry → Map.Entry),
                // 但跳过匿名类(如 TestClass2$1——数字开头的"名称"非法)
                if (isAnonymousClassRef(simpleName(internalName))) {
                    return; // 匿名类不导入
                }
                dotted = dotted.replace('$', '.');
                imports.add(dotted);
            }
        }
    }
}
