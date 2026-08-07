package com.bingbaihanji.bdec.type;

import java.util.Collections;
import java.util.List;

public record JavaType(
        TypeKind kind,
        String internalName,
        String descriptor,
        List<JavaType> typeArguments,
        int arrayDimensions
) {

    public static final JavaType VOID = primitive(TypeKind.VOID, "V");

    public static final JavaType BOOLEAN = primitive(TypeKind.BOOLEAN, "Z");

    public static final JavaType BYTE = primitive(TypeKind.BYTE, "B");

    public static final JavaType SHORT = primitive(TypeKind.SHORT, "S");

    public static final JavaType CHAR = primitive(TypeKind.CHAR, "C");

    public static final JavaType INT = primitive(TypeKind.INT, "I");

    public static final JavaType LONG = primitive(TypeKind.LONG, "J");

    public static final JavaType FLOAT = primitive(TypeKind.FLOAT, "F");

    public static final JavaType DOUBLE = primitive(TypeKind.DOUBLE, "D");

    private static JavaType primitive(TypeKind kind, String descriptor) {
        return new JavaType(kind, null, descriptor, Collections.emptyList(), 0);
    }

    public static JavaType classType(String internalName) {
        return new JavaType(TypeKind.CLASS, internalName,
                "L" + internalName + ";", Collections.emptyList(), 0);
    }

    public static JavaType array(JavaType elementType, int dimensions) {
        StringBuilder desc = new StringBuilder();
        desc.append("[".repeat(Math.max(0, dimensions)));
        desc.append(elementType.descriptor());
        return new JavaType(TypeKind.ARRAY, null,
                desc.toString(), Collections.emptyList(), dimensions);
    }

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
            case CLASS -> internalName.replace('/', '.');
            case ARRAY -> {
                String elemDesc = descriptor.replaceFirst("^\\[+", "");
                JavaType elem = fromDescriptor(elemDesc);
                yield elem.displayName() + "[]".repeat(arrayDimensions);
            }
            default -> descriptor;
        };
    }

    public int slotCount() {
        return (kind == TypeKind.LONG || kind == TypeKind.DOUBLE) ? 2
                : (kind == TypeKind.VOID) ? 0 : 1;
    }
}
