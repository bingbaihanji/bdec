package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.constantpool.BootstrapMethodEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;
import com.bingbaihanji.bdec.type.TypeResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 引导方法(condy/indy)解析工具集合——从 {@link IrBuilder} 中提取的
 * invokedynamic/condy 引导方法与泛型函数式接口重建逻辑(里程碑 Phase 3).
 *
 * <p>所有方法均为无状态静态方法,接收常量池与引导方法列表作为显式参数.</p>
 */
final class BootstrapResolver {

    private BootstrapResolver() {}

    /**
     * 解析 CONSTANT_Dynamic(condy)为可渲染的 {@link DynamicConstantValue}.
     *
     * <p>识别 {@code java.lang.invoke.ConstantBootstraps} 的标准引导方法:</p>
     * <ul>
     *   <li>{@code nullConstant} → null</li>
     *   <li>{@code primitiveClass} → {@code int.class} 等(优先取静态参数中的
     *       基本类型 Class 常量,回退到常量名)</li>
     *   <li>{@code enumConstant} → {@code pkg.Enum.CONSTANT}(静态参数为枚举类型)</li>
     *   <li>{@code getStaticFinal} → {@code pkg.Class.FIELD}(静态参数为声明类与字段名)</li>
     * </ul>
     * {@code invoke} 等惰性求值引导方法无法在源码中表达,回退为类型默认值.
     */
    static DynamicConstantValue resolveCondy(ConstantPoolEntry.CpDynamic dyn,
                                             ConstantPoolEntry[] cp,
                                             List<BootstrapMethodEntry> bootstrapMethods) {
        // 常量名与类型(来自 nameAndType 的字段描述符)
        String name = "condy";
        JavaType type = JavaType.classType("java/lang/Object");
        if (dyn.nameAndTypeIndex() > 0 && dyn.nameAndTypeIndex() < cp.length
                && cp[dyn.nameAndTypeIndex()] instanceof ConstantPoolEntry.CpNameAndType nat) {
            name = ConstantPoolParser.utf8(cp, nat.nameIndex());
            try {
                JavaType parsed = TypeResolver
                        .parseFieldDescriptor(ConstantPoolParser.utf8(cp, nat.descriptorIndex()));
                if (parsed != null) {
                    type = parsed;
                }
            } catch (Exception ignored) {
                // 保持默认 Object 类型
            }
        }

        // 引导方法(owner + 名称 + 静态参数)
        String owner = null;
        String methodName = null;
        List<Integer> args = List.of();
        int bsmIdx = dyn.bootstrapMethodAttrIndex();
        if (bsmIdx >= 0 && bsmIdx < bootstrapMethods.size()) {
            try {
                BootstrapMethodEntry bsm = bootstrapMethods.get(bsmIdx);
                args = bsm.arguments();
                if (bsm.methodRef() > 0 && bsm.methodRef() < cp.length
                        && cp[bsm.methodRef()] instanceof ConstantPoolEntry.CpMethodHandle mh
                        && mh.referenceIndex() > 0 && mh.referenceIndex() < cp.length
                        && cp[mh.referenceIndex()] instanceof ConstantPoolEntry.CpMethodRef mr
                        && mr.nameAndTypeIndex() > 0 && mr.nameAndTypeIndex() < cp.length
                        && cp[mr.nameAndTypeIndex()] instanceof ConstantPoolEntry.CpNameAndType mnt) {
                    owner = ConstantPoolParser.className(cp, mr.classIndex());
                    methodName = ConstantPoolParser.utf8(cp, mnt.nameIndex());
                }
            } catch (Exception ignored) {
                // 引导方法解析为尽力而为
            }
        }

        if (!"java/lang/invoke/ConstantBootstraps".equals(owner)) {
            return DynamicConstantValue.fallback(type);
        }
        switch (methodName) {
            case "nullConstant" -> {
                return DynamicConstantValue.nullConstant(type);
            }
            case "primitiveClass" -> {
                // 静态参数[0]为基本类型的 Class 常量(如 "I");回退到常量名(如 "int")
                String primitiveName = name;
                if (!args.isEmpty() && args.getFirst() > 0 && args.getFirst() < cp.length
                        && cp[args.getFirst()] instanceof ConstantPoolEntry.CpClass cls) {
                    String desc = ConstantPoolParser.utf8(cp, cls.nameIndex());
                    JavaType pt = TypeResolver.parseFieldDescriptor(desc);
                    if (pt != null && pt.kind() != TypeKind.CLASS) {
                        primitiveName = pt.displayName();
                    }
                }
                return DynamicConstantValue.classLiteral(type, primitiveName);
            }
            case "enumConstant" -> {
                // 静态参数[0]为枚举类型
                String enumOwner = null;
                if (!args.isEmpty() && args.getFirst() > 0 && args.getFirst() < cp.length) {
                    enumOwner = ConstantPoolParser.className(cp, args.getFirst())
                            .replace('/', '.');
                }
                return enumOwner != null
                        ? DynamicConstantValue.qualifiedRef(type, enumOwner, name)
                        : DynamicConstantValue.fallback(type);
            }
            case "getStaticFinal" -> {
                // 静态参数[0]为声明类,[1]为字段名字符串
                String declOwner = null;
                String fieldName = null;
                if (args.size() >= 2 && args.get(0) > 0 && args.get(0) < cp.length) {
                    declOwner = ConstantPoolParser.className(cp, args.get(0))
                            .replace('/', '.');
                    if (args.get(1) > 0 && args.get(1) < cp.length
                            && cp[args.get(1)] instanceof ConstantPoolEntry.CpString fs) {
                        fieldName = ConstantPoolParser.utf8(cp, fs.stringIndex());
                    }
                }
                return declOwner != null && fieldName != null
                        ? DynamicConstantValue.qualifiedRef(type, declOwner, fieldName)
                        : DynamicConstantValue.fallback(type);
            }
            default -> {
                // invoke 等惰性求值引导方法:源码无法表达,类型默认值兜底
                return DynamicConstantValue.fallback(type);
            }
        }
    }

    /**
     * 解析引导方法参数,提取实现方法句柄.
     * 对于LambdaMetafactory模式,argument[1]为lambda体/方法引用目标的方法句柄.
     */
    static void resolveBootstrapMethod(BootstrapMethodEntry bsm, ConstantPoolEntry[] cp,
                                       Map<String, Object> annotProps) {
        List<Integer> arguments = bsm.arguments();

        // 特殊处理:makeConcatWithConstants 的 recipe 提取.
        // 引导方法参数:[0]=CONSTANT_String_info(recipe).
        // 不包含实现方法句柄,因此不能走下面的 Lambda 解析路径.
        String indyName = (String) annotProps.get("indyName");
        if ("makeConcatWithConstants".equals(indyName) && !arguments.isEmpty()) {
            int recipeIdx = arguments.get(0);
            if (recipeIdx > 0 && recipeIdx < cp.length
                    && cp[recipeIdx] instanceof ConstantPoolEntry.CpString strEntry) {
                String recipe = ConstantPoolParser.utf8(cp, strEntry.stringIndex());
                if (recipe != null) {
                    annotProps.put("recipe", recipe);
                }
            }
            return; // 没有实现方法句柄,提前返回
        }

        if (arguments.size() < 2) {
            return;
        }

        // 提取 SAM 方法类型描述符(argument[0]),
        // 用于重建带泛型参数的函数式接口类型.
        // 例如 MethodType (Integer,Integer)Integer → BiFunction<Integer,Integer,Integer>
        if (!arguments.isEmpty()) {
            int samTypeIdx = arguments.get(0);
            if (samTypeIdx > 0 && samTypeIdx < cp.length
                    && cp[samTypeIdx] instanceof ConstantPoolEntry.CpMethodType(int descIdx)) {
                String samDesc = ConstantPoolParser.utf8(cp, descIdx);
                if (samDesc != null && !samDesc.isEmpty()) {
                    annotProps.put("samDescriptor", samDesc);
                }
            }
        }

        // 参数1为实现方法句柄
        int implHandleIdx = arguments.get(1);
        if (implHandleIdx <= 0 || implHandleIdx >= cp.length) {
            return;
        }

        ConstantPoolEntry implHandleEntry = cp[implHandleIdx];
        if (!(implHandleEntry instanceof ConstantPoolEntry.CpMethodHandle(int refKind, int refIdx))) {
            return;
        }

        annotProps.put("implKind", refKind);

        if (refIdx <= 0 || refIdx >= cp.length) {
            return;
        }

        ConstantPoolEntry refEntry = cp[refIdx];
        int classIdx = -1;
        int natIdx = -1;
        if (refEntry instanceof ConstantPoolEntry.CpMethodRef(int classIndex, int nameAndTypeIndex)) {
            classIdx = classIndex;
            natIdx = nameAndTypeIndex;
        } else if (refEntry instanceof ConstantPoolEntry.CpInterfaceMethodRef(int classIndex, int nameAndTypeIndex)) {
            classIdx = classIndex;
            natIdx = nameAndTypeIndex;
        } else {
            return;
        }

        if (classIdx > 0) {
            String implOwner = ConstantPoolParser.className(cp, classIdx);
            annotProps.put("implOwner", implOwner);
        }

        if (natIdx > 0 && natIdx < cp.length
                && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
            String implName = ConstantPoolParser.utf8(cp, nat.nameIndex());
            annotProps.put("implName", implName);
            // 提取实现方法描述符,供 BlockReducer 生成正确的 lambda 参数占位符
            String implDesc = ConstantPoolParser.utf8(cp, nat.descriptorIndex());
            annotProps.put("implDescriptor", implDesc);
        }
    }

    /**
     * 用 SAM 方法描述符的类型参数重建泛型函数式接口类型.
     * 例如 BiFunction + (Integer,Integer)Integer → BiFunction<Integer,Integer,Integer>
     */
    static JavaType buildGenericFunctionalType(JavaType rawType, String samDescriptor) {
        JavaType[] samParams = TypeResolver.parseMethodParameterTypes(samDescriptor);
        JavaType samReturn = TypeResolver.parseMethodReturnType(samDescriptor);
        // typeArgs = SAM参数类型 + SAM返回类型(仅引用类型).
        // 基元类型(如 boolean, int)的函数式接口类型参数中不包含返回类型.
        // 例如 Predicate<T> (SAM: T→boolean) = [T], 不包含 boolean.
        List<JavaType> typeArgs = new ArrayList<>();
        for (JavaType p : samParams) {
            typeArgs.add(p);
        }
        if (samReturn.kind() != TypeKind.VOID && samReturn.kind() == TypeKind.CLASS) {
            typeArgs.add(samReturn);
        }
        return new JavaType(TypeKind.CLASS, rawType.internalName(),
                rawType.descriptor(), typeArgs, rawType.arrayDimensions());
    }

    /**
     * 从方法实参推断返回类型的泛型参数.
     * 例如 Arrays.asList(String[]) → List<String>
     */
    static JavaType inferGenericReturnType(String declaringClass,
                                           String methodName,
                                           JavaType returnType,
                                           List<Value> args) {
        // Arrays.asList(T...): 返回 List<T>,从数组元素类型提取 T
        if ("java/util/Arrays".equals(declaringClass) && "asList".equals(methodName)
                && !args.isEmpty()) {
            JavaType argType = args.getFirst().type();
            // 从数组类型中提取元素类型
            if (argType.arrayDimensions() > 0 && argType.descriptor() != null) {
                String elemDesc = argType.descriptor().replaceFirst("^\\[+", "");
                JavaType elemType;
                if (elemDesc.startsWith("L") && elemDesc.endsWith(";")) {
                    elemType = JavaType.classType(
                            elemDesc.substring(1, elemDesc.length() - 1));
                } else {
                    // 基元类型数组:保留原类型但去掉数组维度
                    elemType = new JavaType(argType.kind(),
                            argType.internalName(), argType.descriptor(),
                            argType.typeArguments(), 0);
                }
                return new JavaType(TypeKind.CLASS, returnType.internalName(),
                        returnType.descriptor(), List.of(elemType),
                        returnType.arrayDimensions());
            }
        }
        // Collections.emptyList() / List.of() 等也可在此扩展
        return returnType;
    }
}
