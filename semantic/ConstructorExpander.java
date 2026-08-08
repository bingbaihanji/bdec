package com.bingbaihanji.bdec.semantic;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.IrOpcode;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.ir.Value;
import com.bingbaihanji.bdec.ir.Variable;

/**
 * Folds constructor delegation calls ({@code this(...)} / {@code super(...)})
 * from raw INVOKE {@code <init>} IR instructions.
 *
 * Inspired by CFR's {@code CondenseConstruction} and Vineflower's
 * {@code SimplifyExprentsHelper.isSimpleConstructorInvocation()}.
 *
 * JVM pattern for {@code this(args)} / {@code super(args)}:
 * <pre>
 *   ALOAD 0          → stack: [this]
 *   load args...
 *   INVOKESPECIAL Target.<init>(args)
 * </pre>
 *
 * This pass identifies the {@code <init>} INVOKE tagged by IrBuilder
 * and annotates it as {@code THIS_CONSTRUCTOR} or {@code SUPER_CONSTRUCTOR}
 * based on the target class relationship.
 */
public final class ConstructorExpander {

    /**
     * Expand constructor delegation calls in a method's IR.
     *
     * @return true if any changes were made
     */
    public boolean expand(LinearIr ir, MethodModel method, ClassFileModel classFile) {
        // Only process constructors
        String methodName = method.name();
        if (!"<init>".equals(methodName) && !"<clinit>".equals(methodName)) {
            return false;
        }
        // <clinit> has no constructor delegation
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

            // Get the target class from the annotation (set by IrBuilder from constant pool)
            var ann = insn.getAnnotation(SemanticTag.CONSTRUCTOR_DELEGATION);
            String targetClass = ann != null ? ann.getString(SemanticAnnotation.KEY_TARGET_CLASS) : null;

            if (targetClass == null) {
                // Fallback: try to resolve from operands
                targetClass = resolveTargetClass(insn, classFile);
            }

            if (targetClass == null) {
                continue;
            }

            if (targetClass.equals(thisClassName)) {
                // this(...) call
                insn.addAnnotation(SemanticAnnotation.of(SemanticTag.THIS_CONSTRUCTOR));
                changed = true;
            } else {
                // super(...) call — or a call to another class's constructor
                // Check if it's the superclass
                String superName = classFile.superInternalName();
                if (targetClass.equals(superName)) {
                    insn.addAnnotation(SemanticAnnotation.of(SemanticTag.SUPER_CONSTRUCTOR));
                }
                // If neither same class nor super class, it's a constructor
                // call on another object — leave CONSTRUCTOR_DELEGATION as-is
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Try to determine the target class of a constructor invocation.
     * Uses the nameHint (resolved method name) and any class information available.
     */
    private String resolveTargetClass(IrInstruction inv, ClassFileModel classFile) {
        String nameHint = inv.nameHint();
        if (nameHint == null || !"<init>".equals(nameHint)) {
            return null;
        }

        // Check if the first operand is var0 (this)
        if (!inv.operands().isEmpty()) {
            Value receiver = inv.operands().getFirst();
            if (receiver instanceof Variable v && v.slot() == 0 && !v.isParameter()) {
                // Receiver is 'this' — target is either super class or same class
                // Default to super class for the implicit super() call pattern
                // If it was this(), resolveMethodName would tell us
                // For now, check the method's declaring class from context
            }
        }

        // Try to get the target from the invocation's result type
        if (inv.resultType() != null && inv.resultType().internalName() != null) {
            return inv.resultType().internalName();
        }

        // Fallback: if we can't determine, assume super class
        return classFile.superInternalName();
    }
}
