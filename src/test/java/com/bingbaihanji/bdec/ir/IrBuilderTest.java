package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.type.JavaType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for IrBuilder stack simulation and IR generation.
 */
public class IrBuilderTest {

    private final IrBuilder builder = new IrBuilder();
    private final CfgBuilder cfgBuilder = new CfgBuilder();

    @Test
    public void testConstantFlowsToReturn() {
        // iconst_0; ireturn — the symbolic stack carries the constant to RETURN
        List<Instruction> insns = List.of(
                new Instruction(0, 3, "iconst_0", List.of(), true, false, new int[0], -1),
                new Instruction(1, 172, "ireturn", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()I", JavaType.INT);

        assertNotNull(ir);
        List<IrInstruction> all = ir.instructions();
        assertFalse("should have IR instructions", all.isEmpty());

        // The constant should be an operand of RETURN
        IrInstruction ret = all.stream()
                .filter(i -> i.opcode() == IrOpcode.RETURN).findFirst().orElse(null);
        assertNotNull("should have RETURN", ret);
        assertFalse("RETURN should have constant operand", ret.operands().isEmpty());
        Value operand = ret.operands().getFirst();
        // The constant is now emitted as a CONST IR instruction and referenced
        // via InstructionRef (needed for cross-block value tracking in if-else).
        if (operand instanceof ConstantValue cv) {
            assertEquals(0, cv.value());
        } else if (operand instanceof InstructionRef ref) {
            IrInstruction def = ref.instruction();
            assertEquals(IrOpcode.CONST, def.opcode());
            Value constVal = def.operands().getFirst();
            assertTrue("CONST should have ConstantValue operand", constVal instanceof ConstantValue);
            assertEquals(0, ((ConstantValue) constVal).value());
        } else {
            org.junit.Assert.fail("RETURN operand should be ConstantValue or InstructionRef, got " + operand.getClass());
        }
    }

    @Test
    public void testReturnGeneratesReturnIr() {
        // return (void)
        List<Instruction> insns = List.of(
                new Instruction(0, 177, "return", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()V", JavaType.VOID);

        boolean hasReturn = ir.instructions().stream()
                .anyMatch(i -> i.opcode() == IrOpcode.RETURN);
        assertTrue("should have a RETURN IR instruction", hasReturn);
    }

    @Test
    public void testValueReturnHasOperand() {
        // iconst_1; ireturn
        List<Instruction> insns = List.of(
                new Instruction(0, 4, "iconst_1", List.of(), true, false, new int[0], -1),
                new Instruction(1, 172, "ireturn", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()I", JavaType.INT);

        List<IrInstruction> retInsns = ir.instructions().stream()
                .filter(i -> i.opcode() == IrOpcode.RETURN).toList();
        assertFalse("should have at least one RETURN", retInsns.isEmpty());
        // The return should have an operand (the constant)
        assertFalse("RETURN should have operands", retInsns.getFirst().operands().isEmpty());
    }

    @Test
    public void testArithmeticGeneratesBinaryIr() {
        // iconst_1; iconst_2; iadd; ireturn
        List<Instruction> insns = List.of(
                new Instruction(0, 4, "iconst_1", List.of(), true, false, new int[0], -1),
                new Instruction(1, 5, "iconst_2", List.of(), true, false, new int[0], -1),
                new Instruction(2, 96, "iadd", List.of(), true, false, new int[0], -1),
                new Instruction(3, 172, "ireturn", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()I", JavaType.INT);

        boolean hasBinary = ir.instructions().stream()
                .anyMatch(i -> i.opcode() == IrOpcode.BINARY);
        assertTrue("should have a BINARY IR instruction for iadd", hasBinary);
    }

    @Test
    public void testLoadGeneratesLoadIr() {
        // iload_0; ireturn
        List<Instruction> insns = List.of(
                new Instruction(0, 26, "iload_0", List.of(), true, false, new int[0], -1),
                new Instruction(1, 172, "ireturn", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()I", JavaType.INT);

        boolean hasLoad = ir.instructions().stream()
                .anyMatch(i -> i.opcode() == IrOpcode.LOAD);
        assertTrue("should have a LOAD IR instruction for iload_0", hasLoad);
    }

    @Test
    public void testStoreGeneratesStoreIr() {
        // iconst_0; istore_1; return
        List<Instruction> insns = List.of(
                new Instruction(0, 3, "iconst_0", List.of(), true, false, new int[0], -1),
                new Instruction(1, 60, "istore_1", List.of(), true, false, new int[0], -1),
                new Instruction(2, 177, "return", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()V", JavaType.VOID);

        boolean hasStore = ir.instructions().stream()
                .anyMatch(i -> i.opcode() == IrOpcode.STORE);
        assertTrue("should have a STORE IR instruction for istore_1", hasStore);
    }

    @Test
    public void testEmptyMethodCreatesIr() {
        MethodModel method = new MethodModel(0, "empty", "()V",
                JavaType.VOID, new JavaType[0],
                List.of(), List.of(), 0, 0);
        ControlFlowGraph cfg = cfgBuilder.build(method);
        LinearIr ir = builder.build(cfg, method, new ConstantPoolEntry[0], List.of());
        assertNotNull(ir);
        assertEquals(method, ir.method());
    }

    @Test
    public void testDupInstruction() {
        // iconst_0; dup; istore_1; istore_2; return
        List<Instruction> insns = List.of(
                new Instruction(0, 3, "iconst_0", List.of(), true, false, new int[0], -1),
                new Instruction(1, 89, "dup", List.of(), true, false, new int[0], -1),
                new Instruction(2, 60, "istore_1", List.of(), true, false, new int[0], -1),
                new Instruction(3, 61, "istore_2", List.of(), true, false, new int[0], -1),
                new Instruction(4, 177, "return", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()V", JavaType.VOID);

        long storeCount = ir.instructions().stream()
                .filter(i -> i.opcode() == IrOpcode.STORE).count();
        assertTrue("dup should result in 2 stores", storeCount >= 2);
    }

    @Test
    public void testBranchConditionPreserved() {
        // iconst_0; ifeq 6; iconst_1; goto 7; iconst_2; ireturn
        Instruction ifeq = new Instruction(1, 153, "ifeq", List.of(6), false, false, new int[]{6}, -1);
        Instruction iconst1 = new Instruction(2, 4, "iconst_1", List.of(), true, false, new int[0], -1);
        Instruction gotoInsn = new Instruction(3, 167, "goto", List.of(4), false, false, new int[]{7}, -1);
        Instruction iconst2 = new Instruction(6, 5, "iconst_2", List.of(), true, false, new int[0], -1);
        Instruction ireturn = new Instruction(7, 172, "ireturn", List.of(), false, true, new int[0], -1);

        List<Instruction> insns = List.of(
                new Instruction(0, 3, "iconst_0", List.of(), true, false, new int[0], -1),
                ifeq, iconst1, gotoInsn, iconst2, ireturn);
        LinearIr ir = buildIr(insns, "()I", JavaType.INT);

        boolean hasCondition = ir.instructions().stream()
                .anyMatch(i -> i.opcode() == IrOpcode.CONDITION);
        assertTrue("should have CONDITION IR from ifeq", hasCondition);
    }

    @Test
    public void testFieldLoad() {
        // aload_0; getfield #2; ireturn
        List<Instruction> insns = List.of(
                new Instruction(0, 42, "aload_0", List.of(), true, false, new int[0], -1),
                new Instruction(1, 180, "getfield", List.of(2), true, false, new int[0], -1),
                new Instruction(3, 172, "ireturn", List.of(), false, true, new int[0], -1));
        LinearIr ir = buildIr(insns, "()I", JavaType.INT);

        boolean hasFieldLoad = ir.instructions().stream()
                .anyMatch(i -> i.opcode() == IrOpcode.FIELD_LOAD);
        assertTrue("should have FIELD_LOAD IR", hasFieldLoad);
    }

    // ── helpers ──────────────────────────────────────────────────

    private LinearIr buildIr(List<Instruction> insns, String desc, JavaType returnType) {
        MethodModel method = new MethodModel(0, "test", desc, returnType,
                new JavaType[0], insns, List.of(), 4, 4);
        ControlFlowGraph cfg = cfgBuilder.build(method);
        return builder.build(cfg, method, new ConstantPoolEntry[0], List.of());
    }
}
