package com.bingbaihanji.bdec.cfg;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.type.JavaType;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for dominator tree computation, dominance queries, and dominance frontiers.
 */
public class DominatorTreeTest {

    /** Build a simple diamond CFG: entry → header → (left/right) → merge → exit */
    private ControlFlowGraph buildDiamond() {
        BasicBlock entry = new BasicBlock(-1, List.of());
        BasicBlock header = new BasicBlock(1, List.of(
                new Instruction(0, 153, "ifeq", List.of(3), false, false, new int[]{3}, -1)));
        BasicBlock left = new BasicBlock(2, List.of(
                new Instruction(1, 167, "goto", List.of(4), false, false, new int[]{4}, -1)));
        BasicBlock right = new BasicBlock(3, List.of(
                new Instruction(3, 2, "iconst_1", List.of(), true, false, new int[0], -1)));
        BasicBlock merge = new BasicBlock(4, List.of(
                new Instruction(4, 172, "ireturn", List.of(), false, true, new int[0], -1)));
        BasicBlock exit = new BasicBlock(-2, List.of());

        List<BasicBlock> blocks = List.of(entry, header, left, right, merge, exit);
        List<ControlFlowEdge> edges = List.of(
                ControlFlowEdge.fallThrough(entry, header),
                ControlFlowEdge.trueBranch(header, left),
                ControlFlowEdge.falseBranch(header, right),
                ControlFlowEdge.gotoEdge(left, merge),
                ControlFlowEdge.fallThrough(right, merge),
                ControlFlowEdge.returnEdge(merge, exit)
        );
        return new ControlFlowGraph(null, entry, exit, blocks, edges, List.of());
    }

    /** Build a loop CFG: entry → header → body → header, header → exit */
    private ControlFlowGraph buildLoop() {
        BasicBlock entry = new BasicBlock(-1, List.of());
        BasicBlock header = new BasicBlock(1, List.of(
                new Instruction(0, 154, "ifne", List.of(3), false, false, new int[]{3}, -1)));
        BasicBlock body = new BasicBlock(2, List.of(
                new Instruction(1, 167, "goto", List.of(0), false, false, new int[]{0}, -1)));
        BasicBlock exit = new BasicBlock(-2, List.of());

        List<BasicBlock> blocks = List.of(entry, header, body, exit);
        List<ControlFlowEdge> edges = List.of(
                ControlFlowEdge.fallThrough(entry, header),
                ControlFlowEdge.trueBranch(header, body),
                ControlFlowEdge.falseBranch(header, exit),
                ControlFlowEdge.gotoEdge(body, header)
        );
        return new ControlFlowGraph(null, entry, exit, blocks, edges, List.of());
    }

    @Test
    public void testEntryDominatesAll() {
        ControlFlowGraph cfg = buildDiamond();
        DominatorTree dt = DominatorTree.compute(cfg);

        for (BasicBlock b : cfg.blocks()) {
            assertTrue("entry should dominate B" + b.id(), dt.dominates(cfg.entryBlock(), b));
        }
    }

    @Test
    public void testSelfDominance() {
        ControlFlowGraph cfg = buildDiamond();
        DominatorTree dt = DominatorTree.compute(cfg);

        for (BasicBlock b : cfg.blocks()) {
            assertTrue("block should dominate itself: B" + b.id(), dt.dominates(b, b));
        }
    }

    @Test
    public void testImmediateDominator() {
        ControlFlowGraph cfg = buildDiamond();
        DominatorTree dt = DominatorTree.compute(cfg);

        // In a diamond, header immediately dominates left and right
        BasicBlock header = findBlock(cfg, 1);
        BasicBlock left = findBlock(cfg, 2);
        BasicBlock right = findBlock(cfg, 3);

        assertEquals("idom of B2 should be B1", header, dt.idom(left));
        assertEquals("idom of B3 should be B1", header, dt.idom(right));
    }

    @Test
    public void testMergeBlockDominatedByHeader() {
        ControlFlowGraph cfg = buildDiamond();
        DominatorTree dt = DominatorTree.compute(cfg);

        BasicBlock header = findBlock(cfg, 1);
        BasicBlock merge = findBlock(cfg, 4);

        assertTrue("header should dominate merge", dt.dominates(header, merge));
    }

    @Test
    public void testDominanceFrontier() {
        ControlFlowGraph cfg = buildDiamond();
        DominatorTree dt = DominatorTree.compute(cfg);

        // In a diamond CFG, the branch blocks (left/right) should have merge in
        // their dominance frontier since they dominate predecessors of merge but
        // do NOT strictly dominate merge itself.
        BasicBlock left = findBlock(cfg, 2);
        Map<BasicBlock, Set<BasicBlock>> frontierMap = dt.computeDominanceFrontier();
        Set<BasicBlock> leftFrontier = frontierMap.get(left);
        BasicBlock merge = findBlock(cfg, 4);

        assertNotNull("frontier should not be null", leftFrontier);
        assertTrue("merge should be in left's dominance frontier, got: " + leftFrontier,
                leftFrontier.contains(merge));
    }

    @Test
    public void testLoopHeaderDominatesBody() {
        ControlFlowGraph cfg = buildLoop();
        DominatorTree dt = DominatorTree.compute(cfg);

        BasicBlock header = findBlock(cfg, 1);
        BasicBlock body = findBlock(cfg, 2);

        assertTrue("loop header should dominate body", dt.dominates(header, body));
    }

    @Test
    public void testLoopBodyDoesNotDominateHeader() {
        ControlFlowGraph cfg = buildLoop();
        DominatorTree dt = DominatorTree.compute(cfg);

        BasicBlock header = findBlock(cfg, 1);
        BasicBlock body = findBlock(cfg, 2);

        assertFalse("loop body should NOT dominate header", dt.dominates(body, header));
    }

    @Test
    public void testStrictDominance() {
        ControlFlowGraph cfg = buildDiamond();
        DominatorTree dt = DominatorTree.compute(cfg);

        BasicBlock entry = cfg.entryBlock();
        BasicBlock header = findBlock(cfg, 1);

        // Entry strictly dominates header (dominates and is not equal)
        assertTrue("entry strictly dominates header",
                dt.dominates(entry, header) && !entry.equals(header));
    }

    @Test
    public void testPostDominatorTree() {
        ControlFlowGraph cfg = buildDiamond();
        PostDominatorTree pdt = PostDominatorTree.compute(cfg);

        assertNotNull("post dominator tree should be computed", pdt);

        // exit should post-dominate all blocks
        for (BasicBlock b : cfg.blocks()) {
            assertTrue("exit should post-dominate B" + b.id(),
                    pdt.postDominates(cfg.exitBlock(), b));
        }
    }

    /** Find a block by numeric id from the test CFG. */
    private static BasicBlock findBlock(ControlFlowGraph cfg, int id) {
        for (BasicBlock b : cfg.blocks()) {
            if (b.id() == id) return b;
        }
        throw new AssertionError("Block " + id + " not found");
    }
}
