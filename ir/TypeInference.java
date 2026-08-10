package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSA形式IR的类型推断器.
 * <p>
 * 使用工作列表算法在IR中传播类型信息.
 * 从常量,字段访问和方法调用等已知类型源播种初始类型,
 * 然后通过赋值,phi节点和算术运算传播类型.
 * 使用定值-使用链索引实现O(1)的消费者查找,替代O(n^2)的线性扫描.
 * </p>
 */
public final class TypeInference {

    /** 指令ID到推断类型的映射 */
    private final Map<Integer, JavaType> types = new HashMap<>();

    /**
     * 返回数值类型的宽度等级,用于确定两个数值类型的最小上界.
     * 等级越高表示类型宽度越大(如 double > float > long > int > short > byte).
     */
    private static int rank(JavaType t) {
        return switch (t.kind()) {
            case BYTE -> 0;
            case SHORT -> 1;
            case CHAR -> 1;
            case INT -> 2;
            case LONG -> 3;
            case FLOAT -> 4;
            case DOUBLE -> 5;
            default -> -1;
        };
    }

    /**
     * 对SSA形式的IR执行类型推断.
     *
     * @param ssa SSA形式的中间表示
     * @return 指令ID到推断类型的映射
     */
    public Map<Integer, JavaType> infer(SsaForm ssa) {
        types.clear();
        Deque<IrInstruction> worklist = new ArrayDeque<>();

        // 构建定值-使用链:为每条指令找出其结果的消费者
        Map<Integer, List<IrInstruction>> consumers = buildDefUse(ssa.instructions());

        // 播种:从已知类型源赋予初始类型
        for (IrInstruction insn : ssa.instructions()) {
            JavaType seed = seedType(insn);
            if (seed != null) {
                types.put(insn.id(), seed);
                worklist.add(insn);
            }
        }

        // 传播类型直到不动点
        while (!worklist.isEmpty()) {
            IrInstruction insn = worklist.poll();
            JavaType currentType = types.get(insn.id());
            if (currentType == null) {
                continue;
            }

            // 将类型传播给该指令的所有消费者
            List<IrInstruction> users = consumers.getOrDefault(insn.id(), List.of());
            for (IrInstruction user : users) {
                JavaType propagated = propagate(insn.opcode(), currentType, user.opcode());
                if (propagated != null) {
                    JavaType existing = types.get(user.id());
                    JavaType merged = existing != null ? merge(existing, propagated) : propagated;
                    if (!merged.equals(existing)) {
                        types.put(user.id(), merged);
                        worklist.add(user);
                    }
                }
            }
        }

        return Map.copyOf(types);
    }

    /**
     * 构建指令ID到使用其结果的指令列表的映射(定值-使用链).
     */
    private Map<Integer, List<IrInstruction>> buildDefUse(List<IrInstruction> instructions) {
        Map<Integer, List<IrInstruction>> consumers = new HashMap<>();
        for (IrInstruction insn : instructions) {
            for (Value op : insn.operands()) {
                if (op instanceof InstructionRef ref) {
                    int defId = ref.instruction().id();
                    consumers.computeIfAbsent(defId, k -> new ArrayList<>()).add(insn);
                }
            }
        }
        return consumers;
    }

    /**
     * 确定指令的初始(播种)类型.
     * 常量从其值获取类型,字段加载和方法调用返回声明的返回类型等.
     */
    private JavaType seedType(IrInstruction insn) {
        return switch (insn.opcode()) {
            case CONST -> {
                if (!insn.operands().isEmpty() && insn.operands().getFirst() instanceof ConstantValue cv) {
                    yield cv.type();
                }
                yield null;
            }
            case FIELD_LOAD -> insn.resultType();
            case INVOKE -> insn.resultType();
            case NEW, NEW_ARRAY -> insn.resultType();
            case ARRAY_LENGTH -> JavaType.INT;
            case INSTANCE_OF -> JavaType.INT;
            case COMPARE -> JavaType.INT;
            case BINARY -> insn.resultType();
            case CAST -> insn.resultType(); // 相信显式类型转换的结果类型
            case PHI -> null; // phi节点类型将通过前驱节点推断
            default -> null;
        };
    }

    /**
     * 通过操作传播类型.
     * 根据消费者操作码决定从生产者类型推导出何种结果类型.
     */
    private JavaType propagate(IrOpcode producerOp, JavaType producerType, IrOpcode consumerOp) {
        return switch (consumerOp) {
            case STORE, RETURN -> producerType;
            case BINARY -> {
                if (producerType.kind() == TypeKind.DOUBLE) {
                    yield JavaType.DOUBLE;
                }
                if (producerType.kind() == TypeKind.FLOAT) {
                    yield JavaType.FLOAT;
                }
                if (producerType.kind() == TypeKind.LONG) {
                    yield JavaType.LONG;
                }
                yield JavaType.INT;
            }
            case COMPARE, CONDITION -> JavaType.INT;
            case PHI -> producerType;
            case INVOKE -> producerType;
            default -> producerType;
        };
    }

    /**
     * 合并两个类型(计算最小上界).
     * 如果a和b相同则返回a;若两者均为数值类型则取较宽的那个;
     * 若两者均为引用类型则返回 java/lang/Object.
     */
    private JavaType merge(JavaType a, JavaType b) {
        if (a.equals(b)) {
            return a;
        }
        if (isNumeric(a) && isNumeric(b)) {
            return wider(a, b);
        }
        if (a.kind() == TypeKind.CLASS && b.kind() == TypeKind.CLASS) {
            return JavaType.classType("java/lang/Object");
        }
        return a;
    }

    /**
     * 判断类型是否为数值类型.
     */
    private boolean isNumeric(JavaType t) {
        return switch (t.kind()) {
            case BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    /**
     * 返回两个数值类型中较宽的那个.
     */
    private JavaType wider(JavaType a, JavaType b) {
        return rank(a) >= rank(b) ? a : b;
    }
}
