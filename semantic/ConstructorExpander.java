package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;

/**
 * 构造函数委托展开器.
 *
 * <p>将原始的 INVOKE {@code <init>} IR 指令折叠为构造函数委托调用
 *({@code this(...)} / {@code super(...)}).
 *
 * <p>JVM 中 {@code this(args)} / {@code super(args)} 的字节码模式为:
 * <pre>
 *   ALOAD 0          → 栈: [this]
 *   加载参数...
 *   INVOKESPECIAL Target.&lt;init&gt;(args)
 * </pre>
 *
 * <p>本遍历会识别由 {@code IrBuilder} 标记的 {@code <init>} INVOKE 指令,
 * 并根据目标类与当前类的关系,将其标注为 {@code THIS_CONSTRUCTOR} 或
 * {@code SUPER_CONSTRUCTOR}.
 *
 * <p>设计参考了 CFR 的 {@code CondenseConstruction} 和 Vineflower 的
 * {@code SimplifyExprentsHelper.isSimpleConstructorInvocation()}.
 */
public final class ConstructorExpander {

    /**
     * 在方法的 IR 中展开构造函数委托调用.
     *
     * @param ir       待处理的线性 IR
     * @param method   当前方法模型
     * @param classFile 当前类文件模型
     * @return 如果进行了任何修改则返回 true
     */
    public boolean expand(LinearIr ir, MethodModel method, ClassFileModel classFile) {
        // 仅处理构造函数(<init>)
        String methodName = method.name();
        if (!"<init>".equals(methodName) && !"<clinit>".equals(methodName)) {
            return false;
        }
        // <clinit>(静态初始化块)不含构造函数委托,直接跳过
        if ("<clinit>".equals(methodName)) {
            return false;
        }

        boolean changed = false;
        String thisClassName = classFile.internalName();

        for (IrInstruction insn : ir.instructions()) {
            if (!insn.hasTag(SemanticTag.CONSTRUCTOR_DELEGATION)) {
                continue;
            }
            if (insn.opcode() != IrOpcode.INVOKE) {
                continue;
            }

            // 从注解中获取目标类名(由 IrBuilder 在常量池解析阶段设置)
            var ann = insn.getAnnotation(SemanticTag.CONSTRUCTOR_DELEGATION);
            String targetClass = ann != null ? ann.getString(SemanticAnnotation.KEY_TARGET_CLASS) : null;

            if (targetClass == null) {
                // 回退方案:尝试从操作数中解析目标类
                targetClass = resolveTargetClass(insn, classFile);
            }

            if (targetClass == null) {
                continue;
            }

            if (targetClass.equals(thisClassName)) {
                // 识别为 this(...) 调用
                insn.addAnnotation(SemanticAnnotation.of(SemanticTag.THIS_CONSTRUCTOR));
                changed = true;
            } else {
                // 检查是否为父类构造函数调用 super(...)
                String superName = classFile.superInternalName();
                if (targetClass.equals(superName)) {
                    insn.addAnnotation(SemanticAnnotation.of(SemanticTag.SUPER_CONSTRUCTOR));
                }
                // 如果既不是本类也不是父类,则是对其他对象的构造函数调用,
                // 保留 CONSTRUCTOR_DELEGATION 标记不变
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 尝试确定构造函数调用的目标类.
     *
     * <p>通过名称提示(已解析的方法名)和可用的类信息来推断目标类.
     *
     * @param inv       构造函数调用指令
     * @param classFile 当前类文件模型
     * @return 目标类的内部名称,无法确定时回退到父类
     */
    private String resolveTargetClass(IrInstruction inv, ClassFileModel classFile) {
        String nameHint = inv.nameHint();
        if (nameHint == null || !"<init>".equals(nameHint)) {
            return null;
        }

        // 检查第一个操作数是否为 var0(即 this 引用)
        if (!inv.operands().isEmpty()) {
            Value receiver = inv.operands().getFirst();
            if (receiver instanceof Variable v && v.slot() == 0 && !v.isParameter()) {
                // 接收者是 'this',目标为父类或本类.
                // 默认为父类(对应隐式 super() 调用模式),
                // 若是 this() 调用,resolveMethodName 会提供更多信息.
            }
        }

        // 尝试从调用的结果类型中获取目标类信息
        if (inv.resultType() != null && inv.resultType().internalName() != null) {
            return inv.resultType().internalName();
        }

        // 无法确定时,回退到父类
        return classFile.superInternalName();
    }
}
