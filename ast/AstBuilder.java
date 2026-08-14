package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.LitExpr;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.bytecode.model.AccessFlags;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.FieldModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.util.ClassNames;
import com.bingbaihanji.bdec.util.ParameterNameResolver;

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
        return TypeReferenceUtil.simpleName(internal);
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
                        TypeReferenceUtil.collectImport(com.bingbaihanji.bdec.type.JavaType.classType(declClass),
                                imports, thisClass);
                    }
                }
                if (ann.is(com.bingbaihanji.bdec.semantic.SemanticTag.INDY)) {
                    // lambda/方法引用:SAM 函数式接口类型(如 Function,Supplier)
                    // 与实现所有者类型(方法引用的接收者,如 ArrayList::new 的 ArrayList)
                    // 均需收集 import,否则发射器只能输出全限定名或裸短名.
                    TypeReferenceUtil.collectImport(insn.resultType(), imports, thisClass);
                    String implOwner = ann.getString("implOwner");
                    if (implOwner != null) {
                        TypeReferenceUtil.collectImport(
                                com.bingbaihanji.bdec.type.JavaType.classType(implOwner),
                                imports, thisClass);
                    }
                }
            }
            // NEW指令:从resultType提取类型
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.NEW) {
                TypeReferenceUtil.collectImport(insn.resultType(), imports, thisClass);
            }
            // NEW_ARRAY指令:提取数组元素类型
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.NEW_ARRAY) {
                TypeReferenceUtil.collectImport(insn.resultType(), imports, thisClass);
            }
            // INSTANCE_OF指令:从nameHint提取目标类型
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.INSTANCE_OF
                    && insn.nameHint() != null) {
                TypeReferenceUtil.collectImport(com.bingbaihanji.bdec.type.JavaType.classType(insn.nameHint()),
                        imports, thisClass);
            }
            // Iterator模式:当调用iterator()/hasNext()/next()时,添加Iterator导入
            if (insn.opcode() == com.bingbaihanji.bdec.ir.IrOpcode.INVOKE
                    && insn.nameHint() != null) {
                String mName = insn.nameHint();
                if ("iterator".equals(mName) || "hasNext".equals(mName)
                        || "next".equals(mName)) {
                    TypeReferenceUtil.collectImport(com.bingbaihanji.bdec.type.JavaType.classType(
                            "java/util/Iterator"), imports, thisClass);
                }
                // Map.entry() 调用需要 Map.Entry 的导入
                if ("entry".equals(mName) && insn.resultType() != null
                        && insn.resultType().internalName() != null
                        && insn.resultType().internalName().contains("Map$Entry")) {
                    TypeReferenceUtil.collectImport(com.bingbaihanji.bdec.type.JavaType.classType(
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
                .anyMatch(f -> (f.accessFlags() & AccessFlags.ACC_SYNTHETIC) != 0
                        && f.name().startsWith("this$"));
        // Java 21+ 局部类可能不包含 this$0 字段(通过 requireNonNull 验证后丢弃),
        // 通过检查构造函数的 LVT 中 slot 1 是否名为 this$X 来检测
        if (!isNonStaticInner) {
            isNonStaticInner = classFile.methods().stream()
                    .filter(m -> "<init>".equals(m.name()) && !m.isStatic())
                    .anyMatch(m -> {
                        String slot1 = m.localVarNames().get(1);
                        return slot1 != null && slot1.startsWith("this$");
                    });
        }

        // 缓存类的泛型类型参数,用于后续方法签名解析,避免重复解析.
        // 保持原始名称——注解预置(0x00)延迟到构建 TypeDeclaration 之前,
        // 否则预置后的 "@A T" 会破坏 isClassTypeParam 的类型变量名匹配,
        // 导致方法签名中的 T 无法替换(输出被擦除的 Object).
        List<String> classTypeParams = new ArrayList<>(
                SignatureParser.extractTypeParams(classFile.signature()));

        // 收集类级 JSR-308 类型注解:类型参数声明(0x00)按参数下标缓存,
        // 父类型(0x10)直接收集渲染行——supertype_index 65535 = 父类,
        // 0..interfaces.size()-1 = 接口(按原始 interfaces 数组下标缓存).
        // 泛型实参上的 0x10 注解(如 Base<@A String>,type_path = [TYPE_ARGUMENT(i)])
        // 按完整类型路径缓存到父类/接口各自的映射,渲染实参时注入.
        java.util.Map<Integer, String> classTypeParamAnns = new java.util.HashMap<>();
        List<String> superAnns = new ArrayList<>();
        java.util.Map<Integer, String> interfaceAnnsByIndex = new java.util.HashMap<>();
        java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                java.util.List<String>> superTypeArgAnns = new java.util.LinkedHashMap<>();
        java.util.Map<Integer,
                java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                        java.util.List<String>>> interfaceTypeArgAnnsByIndex =
                new java.util.HashMap<>();
        for (var ta : classFile.typeAnnotations()) {
            if (ta.targetType() == 0x00 && ta.targetInfo().length > 0
                    && ta.targetInfo()[0] < classTypeParams.size()
                    && ta.typePath().isEmpty()) {
                String rendered = renderAnnotation(ta.annotation());
                collectAnnotationImports(ta.annotation(), imports, simpleName);
                classTypeParamAnns.merge(ta.targetInfo()[0], rendered,
                        (a, b) -> a + " " + b);
            } else if (ta.targetType() == 0x10
                    && (ta.targetInfo().length == 0 || ta.targetInfo()[0] == 65535)
                    && ta.typePath().isEmpty()) {
                superAnns.add(renderAnnotation(ta.annotation()));
                collectAnnotationImports(ta.annotation(), imports, simpleName);
            } else if (ta.targetType() == 0x10
                    && ta.targetInfo().length > 0
                    && ta.targetInfo()[0] < classFile.interfaceInternalNames().size()
                    && ta.typePath().isEmpty()) {
                String rendered = renderAnnotation(ta.annotation());
                collectAnnotationImports(ta.annotation(), imports, simpleName);
                interfaceAnnsByIndex.merge(ta.targetInfo()[0], rendered,
                        (a, b) -> a + " " + b);
            } else if (ta.targetType() == 0x10
                    && !ta.typePath().isEmpty()
                    && TypeReferenceUtil.isTypeArgumentPath(ta.typePath())) {
                // 泛型实参上的注解:路径为多级 TYPE_ARGUMENT 链(如
                // [TYPE_ARGUMENT(0), TYPE_ARGUMENT(1)] = 外层实参的内嵌实参),
                // 按完整路径缓存.其他 kind 的路径维持现状丢弃.
                String rendered = renderAnnotation(ta.annotation());
                collectAnnotationImports(ta.annotation(), imports, simpleName);
                java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                        java.util.List<String>> target;
                if (ta.targetInfo().length == 0 || ta.targetInfo()[0] == 65535) {
                    target = superTypeArgAnns;
                } else if (ta.targetInfo()[0] < classFile.interfaceInternalNames().size()) {
                    target = interfaceTypeArgAnnsByIndex.computeIfAbsent(
                            ta.targetInfo()[0], k -> new java.util.LinkedHashMap<>());
                } else {
                    continue;
                }
                target.computeIfAbsent(ta.typePath(), k -> new java.util.ArrayList<>())
                        .add(rendered);
            }
        }

        // 构建字段声明
        for (FieldModel field : classFile.fields()) {
            // 跳过 ACC_SYNTHETIC 字段,但保留内部类的 this$X 外围引用字段,
            // 因为内部类作为独立顶层类输出时需要 this$X 字段来访问外围成员.
            // 若移除该字段,外围字段访问(如 counter)会因作用域原因而无法编译.
            // 同时保留 val$X 捕获局部变量字段——内部类清理重写器(InnerClassRewriter /
            // AnonymousClassRewriter)需要完整的字段信息才能把 val$X 还原为原局部变量名,
            // 否则引用会被 SourceCleanup 兜底成 "int val$local = 0" 造成语义错误.
            // 也保留 $assertionsDisabled——assert 的反编译输出引用它
            //(assert 尚未重构为 assert 语句),缺少字段声明无法编译.
            boolean isSynthetic = (field.accessFlags() & AccessFlags.ACC_SYNTHETIC) != 0;
            boolean isOuterThis = field.name().startsWith("this$");
            boolean isCapturedLocal = field.name().startsWith("val$");
            if (isSynthetic && !isOuterThis && !isCapturedLocal
                    && !"$assertionsDisabled".equals(field.name())) {
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
            // 字段上的注解(RuntimeVisibleAnnotations)
            List<String> fieldAnns = new ArrayList<>();
            for (var ann : field.annotations()) {
                fieldAnns.add(renderAnnotation(ann));
                collectAnnotationImports(ann, imports, simpleName);
            }
            // 字段类型上的 JSR-308 类型注解(RuntimeVisibleTypeAnnotations)
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    List<String>> fieldTypeAnns = buildTypeAnnotationMap(
                    field.typeAnnotations(),
                    com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_FIELD,
                    -1, imports, simpleName);
            FieldDeclaration fd = new FieldDeclaration(
                    field.accessFlags(), field.name(), displayType, init, fieldAnns,
                    fieldTypeAnns);
            members.add(fd);
            // 收集 displayType(泛型签名解析结果,含 typeArguments),
            // 而非 field.type()(描述符原始类型,无泛型)——字段渲染使用
            // displayType 的短名,泛型实参的 import 必须一并收集
            TypeReferenceUtil.collectImport(displayType, imports, simpleName);
        }

        // 构建方法声明
        for (StructuredMethod sm : methods) {
            MethodModel method = sm.method();
            String[] paramNames = buildParameterNames(method);
            JavaType[] paramTypes = method.parameterTypes();
            String methodName = resolveMethodName(method.name(), simpleName,
                    classFile.accessFlags());

            // 对于非静态内部类的构造函数,保留外围 this$0 引用参数.
            // 因为内部类目前以独立顶层类形式输出,this$0 字段和参数都需要保留
            // 以确保字段访问(如 this$0.counter)能正确编译.
            // 未来若实现内部类源码嵌套,可移除此逻辑.

            // 从泛型签名中提取方法级类型参数.
            // 保持原始名称:类型参数声明注解(0x01)延迟到签名替换之后预置,
            // 否则 "@A U" 会破坏类型变量名匹配(输出被擦除的 Object).
            List<String> methodTypeParams = new ArrayList<>(
                    method.signature() != null && !method.signature().isEmpty()
                            ? SignatureParser.extractMethodTypeParams(method.signature())
                            : List.of());
            // 收集方法级类型参数声明注解(0x01),按参数下标缓存
            java.util.Map<Integer, String> methodTypeParamAnns = new java.util.HashMap<>();
            for (var ta : method.typeAnnotations()) {
                if (ta.targetType() == 0x01 && ta.targetInfo().length > 0
                        && ta.targetInfo()[0] < methodTypeParams.size()
                        && ta.typePath().isEmpty()) {
                    String rendered = renderAnnotation(ta.annotation());
                    collectAnnotationImports(ta.annotation(), imports, simpleName);
                    methodTypeParamAnns.merge(ta.targetInfo()[0], rendered,
                            (a, b) -> a + " " + b);
                }
            }

            // 使用泛型签名覆盖返回类型和参数类型(签名包含泛型类型变量,
            // 而描述符仅包含原始类型 Object).签名中的类型变量可能是类的
            // 类型参数或方法自身的类型参数(<T> T genericMethod(T)),两者都替换.
            JavaType returnType = method.returnType();
            if (method.signature() != null && !method.signature().isEmpty()) {
                JavaType[] sigTypes = SignatureParser.parseMethodSignature(
                        method.signature());
                if (sigTypes != null && sigTypes.length == paramTypes.length + 1) {
                    // sigTypes = [param0, param1, ..., returnType]
                    for (int si = 0; si < paramTypes.length; si++) {
                        JavaType sig = sigTypes[si];
                        if (TypeReferenceUtil.isClassTypeParam(sig, classTypeParams)
                                || TypeReferenceUtil.isClassTypeParam(sig, methodTypeParams)
                                || TypeReferenceUtil.hasGenericsOrWildcard(sig)
                                || TypeReferenceUtil.hasTypeVariableArrayElement(sig)) {
                            paramTypes[si] = sig;
                        }
                    }
                    JavaType sigRet = sigTypes[sigTypes.length - 1];
                    if (TypeReferenceUtil.isClassTypeParam(sigRet, classTypeParams)
                            || TypeReferenceUtil.isClassTypeParam(sigRet, methodTypeParams)
                            || TypeReferenceUtil.hasGenericsOrWildcard(sigRet)
                            || TypeReferenceUtil.hasTypeVariableArrayElement(sigRet)) {
                        returnType = sigRet;
                    }
                }
            }

            // 方法声明的 throws 子句(内部名称 → 简单名称)
            List<String> throwsTypes = new ArrayList<>();
            for (String internalName : method.declaredExceptions()) {
                String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
                throwsTypes.add(simple);
                TypeReferenceUtil.collectImport(JavaType.classType(internalName), imports, simpleName);
            }

            // 注解方法元素的默认值(AnnotationDefault 属性)
            String annDefault = method.annotationDefault() != null
                    ? renderAnnotationValue(method.annotationDefault())
                    : null;

            // 方法上的注解(RuntimeVisibleAnnotations)
            List<String> methodAnns = new ArrayList<>();
            for (var ann : method.annotations()) {
                methodAnns.add(renderAnnotation(ann));
                collectAnnotationImports(ann, imports, simpleName);
            }

            // 参数级注解(RuntimeVisibleParameterAnnotations).
            // 条目数可能少于描述符参数数——枚举构造器(前导合成 name/ordinal)
            // 与内部类构造器(this$0)场景 javac 只发射真实参数条目,
            // JVM 反射按尾部对齐:第 i 条对应描述符参数 offset+i
            // (offset = 描述符参数数 - 条目数).
            String[] paramAnns = null;
            if (method.parameterAnnotations() != null) {
                int numP = method.parameterAnnotations().size();
                if (numP > 0 && numP <= paramTypes.length) {
                    int offset = paramTypes.length - numP;
                    boolean any = false;
                    String[] built = new String[paramTypes.length];
                    for (int pi = 0; pi < numP; pi++) {
                        var pAnns = method.parameterAnnotations().get(pi);
                        if (pAnns.isEmpty()) {
                            continue;
                        }
                        StringBuilder sb = new StringBuilder();
                        for (var ann : pAnns) {
                            if (sb.length() > 0) {
                                sb.append(' ');
                            }
                            sb.append(renderAnnotation(ann));
                            collectAnnotationImports(ann, imports, simpleName);
                        }
                        built[offset + pi] = sb.toString();
                        any = true;
                    }
                    if (any) {
                        paramAnns = built;
                    }
                }
            }

            // JSR-308 类型注解(RuntimeVisibleTypeAnnotations):
            // 返回类型(0x14),形式参数类型(0x16,按参数下标对齐),throws 类型(0x17)
            var typeAnns = method.typeAnnotations();
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    List<String>> retTypeAnns = buildTypeAnnotationMap(typeAnns,
                    com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_METHOD_RETURN,
                    -1, imports, simpleName);
            java.util.List<java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    List<String>>> paramTypeAnns = new ArrayList<>();
            for (int pi = 0; pi < paramTypes.length; pi++) {
                paramTypeAnns.add(buildTypeAnnotationMap(typeAnns,
                        com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_FORMAL_PARAMETER,
                        pi, imports, simpleName));
            }
            java.util.List<java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    List<String>>> throwsTypeAnns = new ArrayList<>();
            for (int ti = 0; ti < throwsTypes.size(); ti++) {
                throwsTypeAnns.add(buildTypeAnnotationMap(typeAnns,
                        com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry.TARGET_THROWS,
                        ti, imports, simpleName));
            }
            com.bingbaihanji.bdec.ast.TypeAnnotationSet tas =
                    new com.bingbaihanji.bdec.ast.TypeAnnotationSet(
                            retTypeAnns, List.copyOf(paramTypeAnns), List.copyOf(throwsTypeAnns));

            // 类型参数声明注解(0x01)在签名替换完成后预置
            // (保持原始参数名完成类型变量替换,输出 "<@A U> U m(U u)")
            for (var e : methodTypeParamAnns.entrySet()) {
                int idx = e.getKey();
                if (idx < methodTypeParams.size()) {
                    methodTypeParams.set(idx, e.getValue() + " " + methodTypeParams.get(idx));
                }
            }

            MethodDeclaration decl = new MethodDeclaration(
                    method.accessFlags(),
                    methodName,
                    returnType,
                    paramNames,
                    paramTypes,
                    methodTypeParams,
                    throwsTypes,
                    annDefault,
                    methodAnns,
                    paramAnns,
                    tas,
                    sm.body()
            );
            members.add(decl);

            // 从方法签名中收集类型引用以生成import
            TypeReferenceUtil.collectImport(returnType, imports, simpleName);
            for (JavaType pt : paramTypes) {
                TypeReferenceUtil.collectImport(pt, imports, simpleName);
            }

            // 从方法体中引用的类型收集import.
            // 扫描IR指令中的DECLARING_CLASS注解以发现静态调用目标类型.
            collectBodyImports(sm, imports, simpleName);
        }

        // 确定类型种类:接口,注解,枚举或普通类.
        // JVM 标志:ACC_INTERFACE=0x0200,ACC_ANNOTATION=0x2000,ACC_ENUM=0x4000.
        // 注解类型同时携带 ACC_INTERFACE|ACC_ANNOTATION|ACC_ABSTRACT,
        // 必须先于普通 interface 判断.
        boolean isAnnotationType = (classFile.accessFlags() & AccessFlags.ACC_ANNOTATION) != 0;
        String kind = (classFile.accessFlags() & AccessFlags.ACC_INTERFACE) != 0
                ? (isAnnotationType ? "@interface" : "interface")
                : (classFile.accessFlags() & AccessFlags.ACC_ENUM) != 0 ? "enum" : "class";

        // 解析类签名,重建父类/接口的泛型类型参数(如 Base<String>,List<Integer>).
        // 无签名时(非泛型)退化为简单名称.
        boolean isInterfaceType = (classFile.accessFlags() & AccessFlags.ACC_INTERFACE) != 0;
        JavaType[] superAndInterfaces = SignatureParser.parseClassSignature(classFile.signature());
        boolean hasSuper = !isInterfaceType
                && classFile.superInternalName() != null
                && !"java/lang/Object".equals(classFile.superInternalName());

        // 解析父类名称(排除java.lang.Object的默认继承)
        String superName = null;
        if (hasSuper) {
            superName = (superAndInterfaces != null && superAndInterfaces.length >= 1)
                    ? TypeReferenceUtil.renderClassRef(superAndInterfaces[0], superTypeArgAnns, imports, simpleName)
                    : simpleName(classFile.superInternalName());
            TypeReferenceUtil.collectImport(JavaType.classType(classFile.superInternalName()), imports, simpleName);
        }

        // 解析实现的接口名称列表.
        // 注解类型的隐式父接口 java/lang/annotation/Annotation
        // 不输出(如同类的 java/lang/Object).
        // 接口级 JSR-308 注解(0x10)按原始 interfaces 数组下标缓存,
        // 与名称列表同步过滤,无注解接口用空串占位保持对齐.
        List<String> interfaceNames = new ArrayList<>();
        List<String> interfaceAnns = new ArrayList<>();
        int rawInterfaceIdx = 0;
        int sigInterfaceIdx = 0;
        for (String ifName : classFile.interfaceInternalNames()) {
            String ifAnn = interfaceAnnsByIndex.get(rawInterfaceIdx);
            java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                    java.util.List<String>> ifTypeArgAnns =
                    interfaceTypeArgAnnsByIndex.get(rawInterfaceIdx);
            rawInterfaceIdx++;
            if (isAnnotationType && "java/lang/annotation/Annotation".equals(ifName)) {
                continue;
            }
            // 类签名中接口紧跟父类之后(类:父类占 index 0;接口:无父类,接口从 0 起).
            // 父类若为隐式 java/lang/Object,javac 仍会在签名中占位 Ljava/lang/Object;,
            // 因此偏移量取决于是否为接口,而非显式父类是否存在.
            int sigPos = (isInterfaceType ? 0 : 1) + sigInterfaceIdx;
            String simple = (superAndInterfaces != null && sigPos < superAndInterfaces.length)
                    ? TypeReferenceUtil.renderClassRef(superAndInterfaces[sigPos], ifTypeArgAnns, imports, simpleName)
                    : simpleName(ifName);
            sigInterfaceIdx++;
            interfaceNames.add(simple);
            interfaceAnns.add(ifAnn != null ? ifAnn : "");
            TypeReferenceUtil.collectImport(JavaType.classType(ifName), imports, simpleName);
        }
        boolean anyInterfaceAnns = false;
        for (String a : interfaceAnns) {
            if (!a.isEmpty()) {
                anyInterfaceAnns = true;
                break;
            }
        }
        List<String> interfaceAnnotations = anyInterfaceAnns
                ? interfaceAnns : List.of();

        // 渲染类级注解(RuntimeVisibleAnnotations)
        List<String> typeAnnotations = new ArrayList<>();
        for (var ann : classFile.annotations()) {
            typeAnnotations.add(renderAnnotation(ann));
            // 收集注解引用的类型(RetentionPolicy/ElementType 等)
            collectAnnotationImports(ann, imports, simpleName);
        }

        // 使用缓存的泛型类型参数(已在类头部预先解析).
        // 类型参数声明注解(0x00)在此预置(签名替换完成后,不影响名称匹配).
        for (var e : classTypeParamAnns.entrySet()) {
            int idx = e.getKey();
            if (idx < classTypeParams.size()) {
                classTypeParams.set(idx, e.getValue() + " " + classTypeParams.get(idx));
            }
        }
        TypeDeclaration td = new TypeDeclaration(
                classFile.accessFlags(), simpleName, kind,
                superName, interfaceNames, classTypeParams, members, typeAnnotations,
                superAnns, interfaceAnnotations);

        // 构建内部类友好名称映射:简单内部名称(如 TestClass2$StaticNested) → 友好名称(如 StaticNested).
        // 匿名类(无友好名称)也添加到映射中,键和值均为字节码名称,以确保
        // ExpressionEmitter.typeName() 能将其解析为文件内的简单名称.
        // 先于 import 过滤构建,以便剔除本编译单元自身嵌套类的 import.
        java.util.Map<String, String> innerNames = new java.util.HashMap<>();
        for (var ice : classFile.innerClasses()) {
            if (ice.innerClass() == null) {
                continue;
            }
            String rawSimple = simpleName(ice.innerClass());
            if (ice.simpleName() != null && !ice.simpleName().isEmpty()) {
                // 命名内部类(成员/局部):使用友好名称
                if (!rawSimple.equals(ice.simpleName())) {
                    innerNames.put(rawSimple, ice.simpleName());
                }
            } else if (ClassNames.isAnonymousClassName(rawSimple)) {
                // 匿名类:键和值均为字节码名称,确保简单名称引用
                innerNames.put(rawSimple, rawSimple);
            }
        }
        java.util.Set<String> innerFriendly = new java.util.HashSet<>(innerNames.values());

        // 构建import列表(过滤java.lang.*,同包类型和自身嵌套类)
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
            // 剔除本编译单元自身嵌套类的 import(如 InnerAccess.Inner——
            // Inner 是 InnerAccess 的成员,同文件内声明,无需也无法导入)
            int lastDot = imp.lastIndexOf('.');
            if (lastDot > 0 && imp.substring(0, lastDot).equals(simpleName)
                    && innerFriendly.contains(imp.substring(lastDot + 1))) {
                continue;
            }
            importList.add(imp);
        }
        java.util.Collections.sort(importList);

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
        return ParameterNameResolver.resolveNames(method, "param");
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

    // ── 注解渲染 ──

    /**
     * 从类型注解条目中筛选指定目标,按类型路径分组并渲染为源码行.
     *
     * @param entries    方法/字段的 RuntimeVisibleTypeAnnotations 条目
     * @param targetType 目标类型(字段 0x13 / 返回 0x14 / 参数 0x16 / throws 0x17)
     * @param targetIdx  带下标目标的索引(参数下标,throws 下标),无下标目标传 -1
     * @return 类型路径 → 渲染后注解行列表的映射
     */
    private java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
            List<String>> buildTypeAnnotationMap(
            java.util.List<com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry> entries,
            int targetType, int targetIdx, java.util.Set<String> imports, String simpleName) {
        if (entries == null || entries.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<java.util.List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                List<String>> map = new java.util.LinkedHashMap<>();
        for (var e : entries) {
            if (e.targetType() != targetType) {
                continue;
            }
            if (targetIdx >= 0
                    && (e.targetInfo() == null || e.targetInfo().length == 0
                    || e.targetInfo()[0] != targetIdx)) {
                continue;
            }
            map.computeIfAbsent(e.typePath(), k -> new ArrayList<>())
                    .add(renderAnnotation(e.annotation()));
            collectAnnotationImports(e.annotation(), imports, simpleName);
        }
        return map.isEmpty() ? java.util.Map.of() : map;
    }

    /** 渲染注解为源码行,如 "@Retention(RetentionPolicy.RUNTIME)" */
    private String renderAnnotation(com.bingbaihanji.bdec.bytecode.model.AnnotationEntry ann) {
        return AnnotationRenderer.render(ann, AstBuilder::simpleName);
    }

    /** 渲染注解元素值为 Java 源码片段 */
    private String renderAnnotationValue(Object v) {
        return AnnotationRenderer.renderValue(v, AstBuilder::simpleName);
    }

    /** 收集注解引用的类型以生成 import */
    private void collectAnnotationImports(
            com.bingbaihanji.bdec.bytecode.model.AnnotationEntry ann,
            java.util.Set<String> imports, String simpleName) {
        TypeReferenceUtil.collectImport(JavaType.classType(ann.typeName()), imports, simpleName);
        for (var pair : ann.pairs()) {
            collectAnnotationValueImports(pair.value(), imports, simpleName);
        }
    }

    private void collectAnnotationValueImports(Object v, java.util.Set<String> imports,
                                               String simpleName) {
        switch (v) {
            case com.bingbaihanji.bdec.bytecode.model.AnnotationEntry.EnumValue ev ->
                    TypeReferenceUtil.collectImport(JavaType.classType(ev.typeName()), imports, simpleName);
            case com.bingbaihanji.bdec.bytecode.model.AnnotationEntry.ClassValue cv ->
                    TypeReferenceUtil.collectImport(JavaType.classType(cv.internalName()), imports, simpleName);
            case com.bingbaihanji.bdec.bytecode.model.AnnotationEntry nested ->
                    collectAnnotationImports(nested, imports, simpleName);
            case java.util.List<?> list -> list.forEach(
                    e -> collectAnnotationValueImports(e, imports, simpleName));
            default -> {
            }
        }
    }

}
