package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeResolver;

/**
 * 常量池解析工具集合——从 {@link IrBuilder} 中提取的字段/类型/常量
 * 引用解析逻辑(里程碑 Phase 3).
 *
 * <p>所有方法均为无状态静态方法,接收常量池数组作为显式参数.</p>
 */
final class ConstantPoolResolver {

    private ConstantPoolResolver() {}

    /**
     * 通过字段引用指令从常量池解析字段类型.
     */
    static JavaType resolveFieldType(Instruction insn, ConstantPoolEntry[] cp) {
        if (insn.rawOperands().isEmpty()) {
            return JavaType.classType("java/lang/Object");
        }
        int cpIdx = insn.rawOperands().get(0);
        if (cpIdx <= 0 || cpIdx >= cp.length) {
            return JavaType.classType("java/lang/Object");
        }
        try {
            ConstantPoolEntry entry = cp[cpIdx];
            int natIdx = switch (entry) {
                case ConstantPoolEntry.CpFieldRef fr -> fr.nameAndTypeIndex();
                default -> -1;
            };
            if (natIdx > 0 && natIdx < cp.length
                    && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                String desc = ConstantPoolParser.utf8(cp, nat.descriptorIndex());
                return TypeResolver.parseFieldType(desc);
            }
        } catch (Exception ignored) {
            // 解析失败则返回默认类型
        }
        return JavaType.classType("java/lang/Object");
    }

    /**
     * 通过字段引用指令从常量池解析字段名.
     */
    static String resolveFieldName(Instruction insn, ConstantPoolEntry[] cp) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int cpIdx = insn.rawOperands().get(0);
        if (cpIdx <= 0 || cpIdx >= cp.length) {
            return null;
        }
        try {
            ConstantPoolEntry entry = cp[cpIdx];
            int natIdx = switch (entry) {
                case ConstantPoolEntry.CpFieldRef fr -> fr.nameAndTypeIndex();
                default -> -1;
            };
            if (natIdx > 0 && natIdx < cp.length
                    && cp[natIdx] instanceof ConstantPoolEntry.CpNameAndType nat) {
                return ConstantPoolParser.utf8(cp, nat.nameIndex());
            }
        } catch (Exception ignored) {
            // 解析失败则返回 null
        }
        return null;
    }

    /**
     * 解析字段引用指令的声明类名(用于GETSTATIC指令).
     */
    static String resolveFieldDeclaringClass(Instruction insn, ConstantPoolEntry[] cp) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int cpIdx = insn.rawOperands().get(0);
        if (cpIdx <= 0 || cpIdx >= cp.length) {
            return null;
        }
        try {
            ConstantPoolEntry entry = cp[cpIdx];
            int classIdx = switch (entry) {
                case ConstantPoolEntry.CpFieldRef fr -> fr.classIndex();
                default -> -1;
            };
            if (classIdx > 0 && classIdx < cp.length) {
                return ConstantPoolParser.className(cp, classIdx);
            }
        } catch (Exception ignored) {
            // 解析失败则返回 null
        }
        return null;
    }

    /**
     * 从引用常量池的指令(checkcast,instanceof,anewarray)中解析类类型.
     */
    static JavaType resolveClassType(Instruction insn, ConstantPoolEntry[] cp) {
        int cpIdx = insn.rawOperands().isEmpty() ? 0 : insn.rawOperands().get(0);
        if (cpIdx > 0 && cpIdx < cp.length) {
            String className = ConstantPoolParser.className(cp, cpIdx);
            if (className != null) {
                // 数组类引用(如 anewarray [I):类名即为数组描述符,
                // 须解析为 ARRAY 类型,而非 internalName 含 "[" 的 CLASS,
                // 否则渲染出非法的 "[I[]" 类型名.
                if (className.startsWith("[")) {
                    return TypeResolver.parseFieldType(className);
                }
                return JavaType.classType(className);
            }
        }
        return JavaType.classType("java/lang/Object");
    }

    /**
     * 将常量池条目转换为对应的ConstantValue.
     */
    static ConstantValue cpValue(ConstantPoolEntry entry, ConstantPoolEntry[] pool) {
        return switch (entry) {
            case ConstantPoolEntry.CpInteger i -> new ConstantValue(i.value(), JavaType.INT);
            case ConstantPoolEntry.CpFloat f -> new ConstantValue(f.value(), JavaType.FLOAT);
            case ConstantPoolEntry.CpLong l -> new ConstantValue(l.value(), JavaType.LONG);
            case ConstantPoolEntry.CpDouble d -> new ConstantValue(d.value(), JavaType.DOUBLE);
            case ConstantPoolEntry.CpString s -> new ConstantValue(
                    ConstantPoolParser.utf8(pool, s.stringIndex()),
                    JavaType.classType("java/lang/String"));
            case ConstantPoolEntry.CpClass c -> new ConstantValue(
                    ConstantPoolParser.utf8(pool, c.nameIndex()),
                    JavaType.classType("java/lang/Class"));
            default -> new ConstantValue("<cp:" + entry.tag() + ">", JavaType.classType("java/lang/Object"));
        };
    }
}
