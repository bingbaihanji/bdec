package com.bingbaihanji.bdec.bytecode.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JVM Signature attribute parser.
 *
 * Parses generic type signatures to extract type parameter names
 * and reconstruct generic display names. Handles the most common
 * patterns: class type parameters, simple generic superclasses,
 * and bounded type parameters.
 */
public final class SignatureParser {

    private SignatureParser() {}

    /**
     * Extract formal type parameter names from a class signature.
     *
     * Example: {@code <E:Ljava/lang/Object;>Ljava/util/AbstractQueue<TE;>;}
     * → returns {@code ["E"]}
     */
    public static List<String> extractTypeParams(String signature) {
        List<String> params = new ArrayList<>();
        if (signature == null || signature.isEmpty() || !signature.startsWith("<")) {
            return params;
        }

        int i = 1; // skip '<'
        while (i < signature.length() && signature.charAt(i) != '>') {
            // Read type param name until ':'
            int colon = signature.indexOf(':', i);
            if (colon < 0) {
                break;
            }
            String name = signature.substring(i, colon);
            params.add(name);
            // Skip to after the class bound and interface bounds
            i = colon + 1;
            // Skip bound: L...; or T...;
            while (i < signature.length() && signature.charAt(i) != ':'
                    && signature.charAt(i) != '>') {
                if (signature.charAt(i) == 'L') {
                    i = signature.indexOf(';', i) + 1;
                } else if (signature.charAt(i) == 'T') {
                    i = signature.indexOf(';', i) + 1;
                } else {
                    i++;
                }
            }
            // Skip the ':' separator
            if (i < signature.length() && signature.charAt(i) == ':') {
                i++;
            }
        }
        return params;
    }

    /**
     * Extract method-level type parameters from a method signature.
     *
     * Example: {@code <T:Ljava/lang/Object;>(TT;)TT;}
     * → returns {@code ["T"]}
     */
    public static List<String> extractMethodTypeParams(String signature) {
        return extractTypeParams(signature); // same format: <...> at start
    }

    /**
     * Convert a field or method parameter signature to a readable generic type name.
     *
     * Example: {@code Ljava/util/List<Ljava/lang/String;>;}
     * → {@code java.util.List<java.lang.String>}
     */
    public static String signatureToDisplayName(String sig) {
        if (sig == null || sig.isEmpty()) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            parseTypeSignature(sig, 0, sb);
            return sb.toString();
        } catch (Exception e) {
            return sig; // fallback: raw signature
        }
    }

    /** Parse a type signature starting at position i, appending to sb. Returns next position. */
    private static int parseTypeSignature(String sig, int i, StringBuilder sb) {
        if (i >= sig.length()) {
            return i;
        }
        char c = sig.charAt(i);
        switch (c) {
            case 'B':
                sb.append("byte");
                return i + 1;
            case 'C':
                sb.append("char");
                return i + 1;
            case 'D':
                sb.append("double");
                return i + 1;
            case 'F':
                sb.append("float");
                return i + 1;
            case 'I':
                sb.append("int");
                return i + 1;
            case 'J':
                sb.append("long");
                return i + 1;
            case 'S':
                sb.append("short");
                return i + 1;
            case 'Z':
                sb.append("boolean");
                return i + 1;
            case 'V':
                sb.append("void");
                return i + 1;
            case 'T': {
                // Type variable: Tname;
                int semi = sig.indexOf(';', i);
                sb.append(sig, i + 1, semi);
                return semi + 1;
            }
            case 'L': {
                // Class type: Lpkg/Name; or Lpkg/Name<...>;
                int semi = sig.indexOf(';', i);
                String raw = sig.substring(i + 1, semi);
                // Check for type arguments
                int lt = raw.indexOf('<');
                if (lt >= 0) {
                    String className = raw.substring(0, lt).replace('/', '.');
                    sb.append(className).append('<');
                    int argPos = i + 1 + lt + 1;
                    boolean first = true;
                    while (argPos < i + semi) {
                        if (!first) {
                            sb.append(", ");
                        }
                        first = false;
                        // Type arg could be * (wildcard), +L... (extends), -L... (super), L...; or T...;
                        char ac = sig.charAt(argPos);
                        if (ac == '*') {
                            sb.append('?');
                            argPos++;
                        } else if (ac == '+') {
                            sb.append("? extends ");
                            argPos = parseTypeSignature(sig, argPos + 1, sb);
                        } else if (ac == '-') {
                            sb.append("? super ");
                            argPos = parseTypeSignature(sig, argPos + 1, sb);
                        } else {
                            argPos = parseTypeSignature(sig, argPos, sb);
                        }
                        if (argPos >= i + semi) {
                            break;
                        }
                    }
                    sb.append('>');
                } else {
                    sb.append(raw.replace('/', '.'));
                }
                return semi + 1;
            }
            case '[': {
                int next = parseTypeSignature(sig, i + 1, sb);
                sb.append("[]");
                return next;
            }
            default:
                sb.append('?');
                return i + 1;
        }
    }
}
