package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowEdge;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Debug utility: dump CFG + IR for a method. */
public class CfgDebug {
    public static void main(String[] args) throws Exception {
        String className = args.length > 0 ? args[0] : "TestClass1";
        String methodName = args.length > 1 ? args[1] : "testMethod";
        Path classFile;
        String internalName;
        if (className.contains("/") || className.contains("\\")) {
            classFile = Paths.get(className);
            internalName = className.replace('\\', '/').replace(".class", "");
        } else {
            classFile = Paths.get("target/test-classes/com/bytecode/test/" + className + ".class");
            internalName = "com/bytecode/test/" + className;
        }
        byte[] bytes = Files.readAllBytes(classFile);

        ClassFileModel model = new ClassFileReader().read(internalName, bytes);
        for (MethodModel method : model.methods()) {
            if (!method.name().equals(methodName)) continue;
            if (method.instructions() == null || method.instructions().isEmpty()) continue;

            CfgBuilder cfgBuilder = new CfgBuilder();
            ControlFlowGraph cfg = cfgBuilder.build(method);

            System.out.println("=== CFG for " + method.name() + method.descriptor() + " ===");
            for (BasicBlock b : cfg.blocks()) {
                System.out.println("Block " + b.id() + (b == cfg.entryBlock() ? " [ENTRY]" : "")
                        + (b == cfg.exitBlock() ? " [EXIT]" : ""));
                for (var insn : b.instructions()) {
                    System.out.println("    " + insn);
                }
                for (ControlFlowEdge e : cfg.outgoingOf(b)) {
                    System.out.println("    -> B" + e.target().id() + " [" + e.kind()
                            + (e.switchKey() >= 0 ? " key=" + e.switchKey() : "") + "]");
                }
            }
            System.out.println();

            System.out.println("=== throws for " + method.name() + " ===" + method.declaredExceptions());
            System.out.println("=== LVT for " + method.name() + " ===");
            for (var e : method.localVarEntries()) {
                System.out.println("  slot=" + e.slot() + " name=" + e.name()
                        + " [" + e.startPc() + "," + (e.startPc() + e.length()) + ")");
            }

            System.out.println("=== EXCEPTION RANGES for " + method.name() + " ===");
            for (var r : cfg.exceptionRanges()) {
                System.out.println("  [" + r.startPc() + "," + r.endPc() + ") -> "
                        + (r.handlerBlock() != null ? r.handlerBlock().id() : "null")
                        + " catch=" + r.catchType());
            }

            System.out.println("=== IR for " + method.name() + method.descriptor() + " ===");
            IrBuilder irBuilder = new IrBuilder();
            LinearIr ir = irBuilder.build(cfg, method, model.constantPool(), model.bootstrapMethods());
            for (IrInstruction insn : ir.instructions()) {
                System.out.println("  " + insn.id() + " [block=" + insn.blockId()
                        + " off=" + insn.sourceOffset() + "]: "
                        + insn.opcode() + (insn.operands().isEmpty() ? "" : " " + insn.operands().getFirst()));
            }
        }
    }
}
