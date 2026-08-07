package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.type.JavaType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CfgBuilderTest {

    @Test
    public void testEmptyMethod() {
        MethodModel empty = new MethodModel(0, "test", "()V",
                JavaType.VOID, new JavaType[0],
                List.of(), List.of(), 0, 0);
        ControlFlowGraph cfg = new CfgBuilder().build(empty);
        assertNotNull(cfg);
        assertEquals(2, cfg.blocks().size()); // entry + exit
        assertNotNull(cfg.entryBlock());
        assertNotNull(cfg.exitBlock());
    }

    @Test
    public void testSimpleLinearMethod() {
        Instruction insn1 = new Instruction(0, 4, "iconst_1", List.of(), true, false, new int[0], -1);
        Instruction insn2 = new Instruction(1, 172, "ireturn", List.of(), false, true, new int[0], -1);
        MethodModel method = new MethodModel(0, "test", "()I",
                JavaType.INT, new JavaType[0],
                List.of(insn1, insn2), List.of(), 1, 1);

        ControlFlowGraph cfg = new CfgBuilder().build(method);
        assertTrue(cfg.blocks().size() >= 3); // entry + body + exit
        assertNotNull(cfg.entryBlock());
        assertNotNull(cfg.exitBlock());
    }

    @Test
    public void testBranchingMethod() {
        // ifeq offset+3, iconst_0, goto offset+4, iconst_1, ireturn
        Instruction ifeq = new Instruction(0, 153, "ifeq", List.of(3),
                false, false, new int[]{3}, -1);
        Instruction iconst0 = new Instruction(1, 3, "iconst_0", List.of(), true, false, new int[0], -1);
        Instruction gotoInsn = new Instruction(2, 167, "goto", List.of(4),
                false, false, new int[]{4}, -1);
        Instruction iconst1 = new Instruction(3, 4, "iconst_1", List.of(), true, false, new int[0], -1);
        Instruction ireturn = new Instruction(4, 172, "ireturn", List.of(), false, true, new int[0], -1);

        MethodModel method = new MethodModel(0, "branch", "()I",
                JavaType.INT, new JavaType[0],
                List.of(ifeq, iconst0, gotoInsn, iconst1, ireturn),
                List.of(), 2, 2);

        ControlFlowGraph cfg = new CfgBuilder().build(method);
        assertNotNull(cfg);

        // Verify dominator tree
        DominatorTree dt = cfg.dominatorTree();
        assertNotNull(dt);
        assertTrue("entry should dominate all blocks",
                cfg.blocks().stream().allMatch(b -> dt.dominates(cfg.entryBlock(), b)));
    }
}
