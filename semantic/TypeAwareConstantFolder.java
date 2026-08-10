package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.ir.ConstantValue;
import com.bingbaihanji.bdec.ir.InstructionRef;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.type.TypeKind;

/**
 * 类型感知常量折叠器.
 *
 * <p>当上下文为布尔类型时,将整型常量(0,1)折叠为 {@code false/true}.
 * 仅在无歧义的布尔上下文(布尔返回类型的方法)中进行折叠,
 * 不折叠所有 0/1 常量——普通整型运算结果必须保持为 int 类型.
 *
 * <p>设计参考了 CFR 的 {@code ComparisonOperation.isBooleanComparison()}
 * 和 Procyon 的 {@code TypeAnalysis.verifyResults()} 布尔常量折叠.
 */
public final class TypeAwareConstantFolder {

    /**
     * 沿 InstructionRef 链(包括 PHI 节点)追踪,寻找 ConstantValue.
     *
     * @param v 起始值
     * @return 找到的常量值,如果无法追踪到常量则返回 null
     */
    private static ConstantValue unwrapConstant(Value v) {
        if (v instanceof ConstantValue cv) {
            return cv;
        }
        if (v instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            // 直接的 CONST 指令:第一个操作数即为常量值
            if (def.opcode() == IrOpcode.CONST && !def.operands().isEmpty()
                    && def.operands().getFirst() instanceof ConstantValue cv) {
                return cv;
            }
            // PHI 节点:取第一个操作数并递归追踪
            if (def.opcode() == IrOpcode.PHI && !def.operands().isEmpty()) {
                return unwrapConstant(def.operands().getFirst());
            }
        }
        return null;
    }

    /**
     * 在方法的 IR 中折叠布尔常量.
     *
     * @param ir     待处理的线性 IR
     * @param method 方法模型(用于判断返回类型)
     * @return 如果进行了任何折叠则返回 true
     */
    public boolean fold(LinearIr ir, MethodModel method) {
        boolean changed = false;
        boolean isBooleanReturn = method.returnType() != null
                && method.returnType().kind() == TypeKind.BOOLEAN;

        for (IrInstruction insn : ir.instructions()) {
            // 布尔返回折叠 —— 仅在返回类型为 boolean 的方法中执行
            if (isBooleanReturn && insn.opcode() == IrOpcode.RETURN) {
                changed |= foldBooleanReturn(insn);
            }
        }
        return changed;
    }

    /**
     * 在布尔方法中将操作数为 0/1 的 RETURN 指令折叠为 true/false.
     *
     * @param ret RETURN 指令
     * @return 如果进行了折叠则返回 true
     */
    private boolean foldBooleanReturn(IrInstruction ret) {
        if (ret.operands().isEmpty()) {
            return false;
        }

        Value operand = ret.operands().getFirst();
        // 沿 InstructionRef 链追踪常量值(常量现在以 CONST IR 指令形式表示)
        ConstantValue cv = unwrapConstant(operand);
        if (cv != null) {
            Object val = cv.value();
            if (val instanceof Integer i) {
                boolean boolVal = i != 0;
                ret.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.BOOLEAN_RETURN,
                        SemanticAnnotation.KEY_BOOLEAN_VALUE, boolVal));
                return true;
            }
            if (val instanceof Long l) {
                ret.addAnnotation(SemanticAnnotation.of(
                        SemanticTag.BOOLEAN_RETURN,
                        SemanticAnnotation.KEY_BOOLEAN_VALUE, l != 0L));
                return true;
            }
        }
        return false;
    }
}
