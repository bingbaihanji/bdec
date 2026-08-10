package com.bingbaihanji.bdec.type;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 类型描述符解析器,将 JVM 规范的描述符字符串解析为 {@link JavaType} 实例.
 * 支持字段类型描述符(如 "I","Ljava/lang/String;"),
 * 方法描述符中的参数类型和返回值类型的解析.
 */
public final class TypeResolver {

    /**
     * 解析字段描述符为 JavaType(等价于 {@link #parseFieldType}).
     *
     * @param descriptor JVM 字段描述符
     * @return 对应的 JavaType 实例
     */
    public static JavaType parseFieldDescriptor(String descriptor) {
        return parseType(descriptor, 0).type();
    }

    /**
     * 解析字段类型描述符(如 "I","J","Ljava/lang/String;").
     *
     * @param descriptor JVM 字段描述符
     * @return 对应的 JavaType 实例,若描述符为空则默认返回 Object 类型
     */
    public static JavaType parseFieldType(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return JavaType.classType("java/lang/Object");
        }
        return parseType(descriptor, 0).type();
    }

    /**
     * 解析方法描述符中的参数类型列表.
     * 方法描述符格式:(参数描述符)返回值描述符,如 "(IJ)Ljava/lang/String;".
     *
     * @param methodDescriptor JVM 方法描述符
     * @return 参数类型数组
     * @throws IllegalArgumentException 如果描述符格式不合法
     */
    public static JavaType[] parseMethodParameterTypes(String methodDescriptor) {
        if (!methodDescriptor.startsWith("(")) {
            throw new IllegalArgumentException("Not a method descriptor: " + methodDescriptor);
        }
        int pos = 1;
        List<JavaType> params = new ArrayList<>();
        while (pos < methodDescriptor.length() && methodDescriptor.charAt(pos) != ')') {
            var result = parseType(methodDescriptor, pos);
            params.add(result.type());
            pos = result.nextPos();
        }
        return params.toArray(new JavaType[0]);
    }

    /**
     * 解析方法描述符中的返回值类型.
     *
     * @param methodDescriptor JVM 方法描述符
     * @return 返回值对应的 JavaType 实例
     * @throws IllegalArgumentException 如果描述符格式不合法
     */
    public static JavaType parseMethodReturnType(String methodDescriptor) {
        int closeParen = methodDescriptor.indexOf(')');
        if (closeParen < 0) {
            throw new IllegalArgumentException("Not a method descriptor: " + methodDescriptor);
        }
        return parseType(methodDescriptor, closeParen + 1).type();
    }

    /**
     * 从指定位置开始递归解析一个类型描述符片段.
     * <ul>
     *   <li>单个字符 V/Z/B/S/C/I/J/F/D → 对应的基本类型</li>
     *   <li>L + 内部名称 + ; → 类类型</li>
     *   <li>[ + 元素类型 → 数组类型(递归)</li>
     * </ul>
     *
     * @param desc 描述符字符串
     * @param pos  当前解析起始位置
     * @return 解析结果,包含解析出的类型和下一个待解析位置
     */
    private static ParseResult parseType(String desc, int pos) {
        char c = desc.charAt(pos);
        return switch (c) {
            case 'V' -> new ParseResult(JavaType.VOID, pos + 1);
            case 'Z' -> new ParseResult(JavaType.BOOLEAN, pos + 1);
            case 'B' -> new ParseResult(JavaType.BYTE, pos + 1);
            case 'S' -> new ParseResult(JavaType.SHORT, pos + 1);
            case 'C' -> new ParseResult(JavaType.CHAR, pos + 1);
            case 'I' -> new ParseResult(JavaType.INT, pos + 1);
            case 'J' -> new ParseResult(JavaType.LONG, pos + 1);
            case 'F' -> new ParseResult(JavaType.FLOAT, pos + 1);
            case 'D' -> new ParseResult(JavaType.DOUBLE, pos + 1);
            case 'L' -> {
                // 类类型:格式为 L<内部名称>;,找到分号为止
                int end = desc.indexOf(';', pos);
                String internalName = desc.substring(pos + 1, end);
                yield new ParseResult(JavaType.classType(internalName), end + 1);
            }
            case '[' -> {
                // 数组类型:前缀 '[' 后递归解析元素类型
                var elem = parseType(desc, pos + 1);
                yield new ParseResult(JavaType.array(elem.type(), 1 + elem.type().arrayDimensions()), elem.nextPos());
            }
            default -> throw new IllegalArgumentException("Unknown type descriptor char: " + c + " in " + desc);
        };
    }

    /**
     * 内部记录类,封装类型解析的中间结果.
     *
     * @param type    解析出的 JavaType
     * @param nextPos 下一个待解析字符的索引位置
     */
    private record ParseResult(JavaType type, int nextPos) {}
}
