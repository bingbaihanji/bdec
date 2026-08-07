package com.bingbaihanji.bdec.decompiler.cfg;

import java.util.List;
import java.util.Set;

public interface DominatorTree {

    boolean dominates(BasicBlock dominator, BasicBlock node);

    BasicBlock immediateDominator(BasicBlock node);

    Set<BasicBlock> dominators(BasicBlock node);

    List<BasicBlock> children(BasicBlock node);
}
