package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;
import com.bingbaihanji.bdec.ir.IrInstruction;
import com.bingbaihanji.bdec.ir.LinearIr;

import java.util.ArrayList;
import java.util.List;

/**
 * 基本块组——按 CFG 结构聚合的连续基本块序列.
 * 从 {@link BlockReducer} 中提升为包级类,供翻译器共享
 * (原为 BlockReducer 的私有嵌套类).
 */
final class BlockGroup {

        /** 组内的基本块列表 */
        private final List<BasicBlock> blocks = new ArrayList<>();

        BlockGroup(BasicBlock first) {blocks.add(first);}

        void add(BasicBlock b) {blocks.add(b);}

        BasicBlock first() {return blocks.getFirst();}

        BasicBlock last() {return blocks.getLast();}

        List<BasicBlock> blocks() {return blocks;}

        /** 收集组内所有基本块的全部 IR 指令 */
        List<IrInstruction> allIrInstructions(LinearIr ir) {
            List<IrInstruction> result = new ArrayList<>();
            for (BasicBlock b : blocks) {
                result.addAll(ir.instructionsOf(b));
            }
            return result;
        }
    }

