package com.bingbaihanji.bdec.type;

import java.util.ArrayList;
import java.util.List;

public final class TypeResolver {

    public static JavaType parseFieldDescriptor(String descriptor) {
        return parseType(descriptor, 0).type();
    }

    /** Parse a field type descriptor (e.g., "I", "J", "Ljava/lang/String;"). */
    public static JavaType parseFieldType(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return JavaType.classType("java/lang/Object");
        }
        return parseType(descriptor, 0).type();
    }

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

    public static JavaType parseMethodReturnType(String methodDescriptor) {
        int closeParen = methodDescriptor.indexOf(')');
        if (closeParen < 0) {
            throw new IllegalArgumentException("Not a method descriptor: " + methodDescriptor);
        }
        return parseType(methodDescriptor, closeParen + 1).type();
    }

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
                int end = desc.indexOf(';', pos);
                String internalName = desc.substring(pos + 1, end);
                yield new ParseResult(JavaType.classType(internalName), end + 1);
            }
            case '[' -> {
                var elem = parseType(desc, pos + 1);
                yield new ParseResult(JavaType.array(elem.type(), 1 + elem.type().arrayDimensions()), elem.nextPos());
            }
            default -> throw new IllegalArgumentException("Unknown type descriptor char: " + c + " in " + desc);
        };
    }

    private record ParseResult(JavaType type, int nextPos) {}
}
