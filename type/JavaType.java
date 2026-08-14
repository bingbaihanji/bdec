package com.bingbaihanji.bdec.type;

import java.util.Collections;
import java.util.List;

/**
 * Java 类型表示,封装类型的种类,内部名称,描述符,泛型参数和数组维度.
 * 支持基本类型,类类型,数组类型的统一建模,并提供显示名称生成,
 * 本地变量槽位计算等工具方法.
 *
 * @param kind            类型种类枚举
 * @param internalName    JVM 内部名称(如 "java/lang/String");ARRAY 种类下
 *                        为元素链首个类型变量名(其余场景恒 null),供签名
 *                        覆盖闸门的 isClassTypeParam 判定使用
 * @param descriptor      JVM 类型描述符(如 "I","Ljava/lang/String;")
 * @param typeArguments   泛型类型参数列表
 * @param arrayDimensions 数组维度数(0 表示非数组类型)
 * @param element         数组元素类型(仅 ARRAY 种类由 {@link #array} 工厂
 *                        存储;其余种类及旧式五参构造为 null,elementOf
 *                        在 null 时回退描述符重建)
 */
public record JavaType(
        TypeKind kind,
        String internalName,
        String descriptor,
        List<JavaType> typeArguments,
        int arrayDimensions,
        JavaType element
) {

    /**
     * 五参兼容构造:element 为 null,与旧记录形态调用点完全一致.
     * 经此构造的数组(element=null)在 elementOf/displayName 中
     * 走描述符重建旧路径,行为不变.
     */
    public JavaType(TypeKind kind, String internalName, String descriptor,
                    List<JavaType> typeArguments, int arrayDimensions) {
        this(kind, internalName, descriptor, typeArguments, arrayDimensions, null);
    }

    /** void 基本类型 */
    public static final JavaType VOID = primitive(TypeKind.VOID, "V");

    /** boolean 基本类型 */
    public static final JavaType BOOLEAN = primitive(TypeKind.BOOLEAN, "Z");

    /** byte 基本类型 */
    public static final JavaType BYTE = primitive(TypeKind.BYTE, "B");

    /** short 基本类型 */
    public static final JavaType SHORT = primitive(TypeKind.SHORT, "S");

    /** char 基本类型 */
    public static final JavaType CHAR = primitive(TypeKind.CHAR, "C");

    /** int 基本类型 */
    public static final JavaType INT = primitive(TypeKind.INT, "I");

    /** long 基本类型 */
    public static final JavaType LONG = primitive(TypeKind.LONG, "J");

    /** float 基本类型 */
    public static final JavaType FLOAT = primitive(TypeKind.FLOAT, "F");

    /** double 基本类型 */
    public static final JavaType DOUBLE = primitive(TypeKind.DOUBLE, "D");

    /**
     * 创建基本类型的工厂方法.
     *
     * @param kind       类型种类
     * @param descriptor JVM 描述符
     * @return 基本类型实例
     */
    private static JavaType primitive(TypeKind kind, String descriptor) {
        return new JavaType(kind, null, descriptor, Collections.emptyList(), 0);
    }

    /**
     * 创建类类型的工厂方法.
     *
     * @param internalName JVM 内部名称(如 "java/lang/String")
     * @return 类类型实例
     */
    public static JavaType classType(String internalName) {
        return new JavaType(TypeKind.CLASS, internalName,
                "L" + internalName + ";", Collections.emptyList(), 0);
    }

    /**
     * 创建泛型类型变量的工厂方法.
     *
     * <p>契约:descriptor 保持 JVM 签名字段格式 {@code "T" + name + ";"}
     * (如 {@code TT;}),displayName 返回裸变量名——与旧的"CLASS 伪装"
     * 表示字节级一致,兼容依赖 {@code descriptor().startsWith("T")}
     * 启发式的消费点(如 MethodRefRewriter).</p>
     *
     * @param name 类型变量名(如 "T","E")
     * @return 类型变量实例
     */
    public static JavaType typeVariable(String name) {
        return new JavaType(TypeKind.TYPE_VARIABLE, name, "T" + name + ";",
                Collections.emptyList(), 0);
    }

    /**
     * 创建数组类型的工厂方法.
     *
     * <p>元素类型存入 {@code element} 组件(descriptor 仍为
     * {@code "[" + elementType.descriptor()} 的字节级形态,泛型信息
     * 不再经描述符擦除丢失);若元素链含类型变量,internalName 携带
     * 首个类型变量名——AstBuilder 签名覆盖闸门的
     * {@code isClassTypeParam} 按 internalName 与类型参数名匹配,
     * 从而使 {@code List<T>[]} 这类签名自动放行覆盖擦除描述符。</p>
     *
     * @param elementType 数组元素类型
     * @param dimensions  数组维度数
     * @return 数组类型实例
     */
    public static JavaType array(JavaType elementType, int dimensions) {
        String desc = "[".repeat(Math.max(0, dimensions)) +
                elementType.descriptor();
        return new JavaType(TypeKind.ARRAY, firstTypeVariableName(elementType),
                desc, Collections.emptyList(), dimensions, elementType);
    }

    /**
     * 获取数组类型的元素类型(剥离最外层一维).
     *
     * <p>优先返回工厂存储的元素(保留泛型参数,如 {@code List<T>[]} →
     * {@code List<T>});element 为 null(旧式五参构造)时回退描述符
     * 重建,行为与旧实现一致。</p>
     *
     * @param arrayType 数组类型
     * @return 元素类型,若输入非数组类型则原样返回
     */
    public static JavaType elementOf(JavaType arrayType) {
        if (arrayType.kind() != TypeKind.ARRAY || arrayType.arrayDimensions() == 0) {
            return arrayType;
        }
        if (arrayType.element() != null) {
            return arrayType.element();
        }
        String elemDesc = arrayType.descriptor().replaceFirst("^\\[", "");
        return fromDescriptor(elemDesc);
    }

    /**
     * 深度优先查找类型树中首个类型变量的名字.
     *
     * <p>CLASS/WILDCARD 沿 typeArguments 递归,ARRAY 沿 element 递归,
     * 未找到返回 null。</p>
     *
     * @param type 待检查的类型
     * @return 首个类型变量名,无则 null
     */
    private static String firstTypeVariableName(JavaType type) {
        if (type == null) {
            return null;
        }
        switch (type.kind()) {
            case TYPE_VARIABLE -> {
                return type.internalName();
            }
            case ARRAY -> {
                return firstTypeVariableName(type.element());
            }
            case CLASS, WILDCARD -> {
                for (JavaType arg : type.typeArguments()) {
                    String found = firstTypeVariableName(arg);
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 从 JVM 描述符解析出对应的 JavaType 实例.
     *
     * @param desc JVM 类型描述符
     * @return 对应的 JavaType 实例
     */
    private static JavaType fromDescriptor(String desc) {
        return switch (desc.charAt(0)) {
            case 'V' -> VOID;
            case 'Z' -> BOOLEAN;
            case 'B' -> BYTE;
            case 'S' -> SHORT;
            case 'C' -> CHAR;
            case 'I' -> INT;
            case 'J' -> LONG;
            case 'F' -> FLOAT;
            case 'D' -> DOUBLE;
            case 'L' -> classType(desc.substring(1, desc.length() - 1));
            default -> {
                // 类型变量描述符(签名格式 "T" + 名字 + ";",如 "TT;"):
                // 经 elementOf 重建数组元素类型时也产 TYPE_VARIABLE,
                // 与 SignatureParser / JavaType.typeVariable 的表示一致,
                // 不再伪装为 CLASS(旧伪装 internalName 携带整个 "TT;",
                // 渲染出非法的 "TT;[]")。
                if (desc.length() > 2 && desc.charAt(0) == 'T'
                        && desc.charAt(desc.length() - 1) == ';') {
                    yield typeVariable(desc.substring(1, desc.length() - 1));
                }
                // 其他无法识别的描述符(如多维数组元素 "[I")保持旧行为
                yield new JavaType(TypeKind.CLASS, desc, desc, Collections.emptyList(), 0);
            }
        };
    }

    /**
     * 生成类型的友好显示名称,供源代码输出使用.
     * <ul>
     *   <li>基本类型返回对应的 Java 关键字(int,long 等)</li>
     *   <li>类类型返回内部名称转为点分形式,并处理泛型参数</li>
     *   <li>类型变量返回裸变量名(存储在 internalName)</li>
     *   <li>数组类型递归拼接 "[]" 后缀</li>
     * </ul>
     *
     * @return 类型的显示名称字符串
     */
    public String displayName() {
        return switch (kind) {
            case VOID -> "void";
            case BOOLEAN -> "boolean";
            case BYTE -> "byte";
            case SHORT -> "short";
            case CHAR -> "char";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case CLASS -> {
                String name = internalName != null ? internalName.replace('/', '.') : "?";
                // 为可读性去除 java.lang. 前缀(如 Object,String 等常用类型)
                if (name.startsWith("java.lang.") && name.indexOf('.', 10) < 0) {
                    name = name.substring(10); // "java.lang." 长度为 10
                }
                if (!typeArguments.isEmpty()) {
                    StringBuilder sb = new StringBuilder(name);
                    sb.append('<');
                    for (int i = 0; i < typeArguments.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(typeArguments.get(i).displayName());
                    }
                    sb.append('>');
                    yield sb.toString();
                }
                yield name;
            }
            case ARRAY -> {
                // 优先经存储元素渲染(保留泛型参数,如 List<T>[]);
                // 嵌套创建(SignatureParser 每层 1 维)与维度累积创建
                // (TypeResolver 外层维度为 1+内层维度)统一按
                // 元素链递归,括号数 = 各层贡献之和.
                if (arrayDimensions > 0 && element != null) {
                    if (element.kind() == TypeKind.ARRAY) {
                        int remaining = Math.max(1,
                                arrayDimensions - element.arrayDimensions());
                        yield element.displayName() + "[]".repeat(remaining);
                    }
                    yield element.displayName() + "[]".repeat(arrayDimensions);
                }
                // 无存储元素(旧式五参构造)或维度 0:描述符重建旧路径
                String elemDesc = descriptor.replaceFirst("^\\[+", "");
                JavaType elem = fromDescriptor(elemDesc);
                yield elem.displayName() + "[]".repeat(arrayDimensions);
            }
            case WILDCARD -> {
                // 边界类型可能自带泛型参数(? extends Comparable<String>),
                // 从边界重建显示名以保留嵌套泛型(internalName 在解析时
                // 仅取了边界的基础名).
                if (!typeArguments.isEmpty()) {
                    String base = typeArguments.getFirst().displayName();
                    if (internalName != null && internalName.startsWith("? super ")) {
                        yield "? super " + base;
                    }
                    yield "? extends " + base;
                }
                yield internalName != null ? internalName : "?";
            }
            case TYPE_VARIABLE -> internalName != null ? internalName : "?";
            default -> descriptor;
        };
    }

    /**
     * 返回该类型在 JVM 本地变量表中占用的槽位数量.
     * long 和 double 占 2 个槽位,void 占 0 个,其他类型占 1 个.
     *
     * @return 槽位数量
     */
    public int slotCount() {
        return (kind == TypeKind.LONG || kind == TypeKind.DOUBLE) ? 2
                : (kind == TypeKind.VOID) ? 0 : 1;
    }
}
