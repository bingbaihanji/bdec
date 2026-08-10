package com.bingbaihanji.bdec.type;

import java.util.Collections;
import java.util.List;

/**
 * Java 类型表示,封装类型的种类,内部名称,描述符,泛型参数和数组维度.
 * 支持基本类型,类类型,数组类型的统一建模,并提供显示名称生成,
 * 本地变量槽位计算等工具方法.
 *
 * @param kind            类型种类枚举
 * @param internalName    JVM 内部名称(如 "java/lang/String")
 * @param descriptor      JVM 类型描述符(如 "I","Ljava/lang/String;")
 * @param typeArguments   泛型类型参数列表
 * @param arrayDimensions 数组维度数(0 表示非数组类型)
 */
public record JavaType(
        TypeKind kind,
        String internalName,
        String descriptor,
        List<JavaType> typeArguments,
        int arrayDimensions
) {

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
     * 创建数组类型的工厂方法.
     *
     * @param elementType 数组元素类型
     * @param dimensions  数组维度数
     * @return 数组类型实例
     */
    public static JavaType array(JavaType elementType, int dimensions) {
        String desc = "[".repeat(Math.max(0, dimensions)) +
                elementType.descriptor();
        return new JavaType(TypeKind.ARRAY, null,
                desc, Collections.emptyList(), dimensions);
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
            default -> new JavaType(TypeKind.CLASS, desc, desc, Collections.emptyList(), 0);
        };
    }

    /**
     * 生成类型的友好显示名称,供源代码输出使用.
     * <ul>
     *   <li>基本类型返回对应的 Java 关键字(int,long 等)</li>
     *   <li>类类型返回内部名称转为点分形式,并处理泛型参数</li>
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
                // 从描述符中提取元素类型的描述符部分
                String elemDesc = descriptor.replaceFirst("^\\[+", "");
                JavaType elem = fromDescriptor(elemDesc);
                yield elem.displayName() + "[]".repeat(arrayDimensions);
            }
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
