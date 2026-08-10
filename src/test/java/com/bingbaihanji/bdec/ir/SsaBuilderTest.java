package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.cfg.DominatorTree;
import com.bingbaihanji.bdec.type.JavaType;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for SSA construction and related optimization passes.
 */
public class SsaBuilderTest {

    private final IrBuilder irBuilder = new IrBuilder();

    private final CfgBuilder cfgBuilder = new CfgBuilder();

    private final SsaBuilder ssaBuilder = new SsaBuilder();

    @Test
    public void testSsaConstructionOnStraightLine() {
        // iconst_0; istore_1; iload_1; ireturn — single def, no PHI needed
        List<Instruction> insns = List.of(
                new Instruction(0, 3, "iconst_0", List.of(), true, false, new int[0], -1),
                new Instruction(1, 60, "istore_1", List.of(), true, false, new int[0], -1),
                new Instruction(2, 27, "iload_1", List.of(), true, false, new int[0], -1),
                new Instruction(3, 172, "ireturn", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()I", JavaType.INT);

        SsaForm ssa = ssaBuilder.build(ir);
        assertNotNull(ssa);
        assertFalse("straight-line code should still produce SSA form", ssa.instructions().isEmpty());
    }

    @Test
    public void testSsaOnBranch() {
        // ifeq L1; iconst_1; goto L2; L1: iconst_2; L2: istore_1; iload_1; ireturn
        Instruction ifeq = new Instruction(0, 153, "ifeq", List.of(4), false, false, new int[]{4}, -1);
        Instruction iconst1 = new Instruction(1, 4, "iconst_1", List.of(), true, false, new int[0], -1);
        Instruction gotoInsn = new Instruction(2, 167, "goto", List.of(5), false, false, new int[]{5}, -1);
        Instruction iconst2 = new Instruction(4, 5, "iconst_2", List.of(), true, false, new int[0], -1);
        Instruction istore = new Instruction(5, 60, "istore_1", List.of(), true, false, new int[0], -1);
        Instruction iload = new Instruction(6, 27, "iload_1", List.of(), true, false, new int[0], -1);
        Instruction ireturn = new Instruction(7, 172, "ireturn", List.of(), false, true, new int[0], -1);

        LinearIr ir = buildIr(List.of(ifeq, iconst1, gotoInsn, iconst2, istore, iload, ireturn),
                "()I", JavaType.INT);
        SsaForm ssa = ssaBuilder.build(ir);
        assertNotNull(ssa);
        // Should have produced SSA form without crashing
        assertTrue(ssa.instructions().size() > 0);
    }

    @Test
    public void testDeadCodeElimination() {
        DeadCodeElimination dce = new DeadCodeElimination();
        // Create a dead instruction (const never used) + live return
        IrInstruction deadConst = IrInstruction.constInsn(0,
                new ConstantValue(42, JavaType.INT), 0, 1);
        IrInstruction liveReturn = IrInstruction.returnInsn(1,
                new ConstantValue(0, JavaType.INT), 1, 1);
        liveReturn.setResultValue(null);

        List<IrInstruction> result = dce.eliminate(List.of(deadConst, liveReturn));
        assertEquals("should have 1 instruction after DCE (only return)", 1, result.size());
        assertEquals(IrOpcode.RETURN, result.getFirst().opcode());
    }

    @Test
    public void testCopyPropagation() {
        CopyPropagation cp = new CopyPropagation();
        Variable var0 = new Variable(0, 0, JavaType.INT, false, 0);
        ConstantValue cv = new ConstantValue(42, JavaType.INT);

        // store: var0 = 42; load: var0 (should be replaced)
        IrInstruction store = IrInstruction.store(0, var0, cv, 0, 1);
        IrInstruction load = IrInstruction.load(1, var0, 1, 1);
        load.setResultValue(new InstructionRef(load, JavaType.INT));

        List<IrInstruction> result = cp.propagate(List.of(store, load));
        assertEquals(2, result.size());

        // The LOAD should now have the constant as operand instead of the variable
        IrInstruction replacedLoad = result.get(1);
        assertTrue("LOAD should reference ConstantValue after propagation",
                replacedLoad.operands().getFirst() instanceof ConstantValue);
    }

    @Test
    public void testSsaOnSimpleStore() {
        // iconst_0; istore_0; return — single definition
        List<Instruction> insns = List.of(
                new Instruction(0, 3, "iconst_0", List.of(), true, false, new int[0], -1),
                new Instruction(1, 59, "istore_0", List.of(), true, false, new int[0], -1),
                new Instruction(2, 177, "return", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()V", JavaType.VOID);

        SsaForm ssa = ssaBuilder.build(ir);
        assertNotNull(ssa);
        // SSA should produce something; the main goal is no crash
        assertNotNull(ssa.instructions());
    }

    @Test
    public void testTypeInferenceSeedTypes() {
        TypeInference ti = new TypeInference();
        // Create a const int instruction
        IrInstruction constInsn = IrInstruction.constInsn(0,
                new ConstantValue(42, JavaType.INT), 0, 1);
        IrInstruction constStr = IrInstruction.constInsn(1,
                new ConstantValue("hello", JavaType.classType("java/lang/String")), 1, 1);

        // Build a minimal SSA form
        ControlFlowGraph cfg = buildEmptyCfg();
        SsaForm ssa = new SsaForm(cfg, DominatorTree.compute(cfg),
                List.of(constInsn, constStr), Map.of());

        var types = ti.infer(ssa);
        assertEquals(JavaType.INT, types.get(constInsn.id()));
        assertEquals(JavaType.classType("java/lang/String"), types.get(constStr.id()));
    }

    // ── helpers ──────────────────────────────────────────────────

    private LinearIr buildIr(List<Instruction> insns, String desc, JavaType returnType) {
        MethodModel method = new MethodModel(0, "test", desc, returnType,
                new JavaType[0], insns, List.of(), 4, 4);
        ControlFlowGraph cfg = cfgBuilder.build(method);
        return irBuilder.build(cfg, method, new ConstantPoolEntry[0], List.of());
    }

    private ControlFlowGraph buildEmptyCfg() {
        com.bingbaihanji.bdec.cfg.BasicBlock entry =
                new com.bingbaihanji.bdec.cfg.BasicBlock(-1, List.of());
        com.bingbaihanji.bdec.cfg.BasicBlock exit =
                new com.bingbaihanji.bdec.cfg.BasicBlock(-2, List.of());
        return new ControlFlowGraph(null, entry, exit,
                List.of(entry, exit), List.of(), List.of());
    }
}
