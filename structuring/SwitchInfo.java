package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Map;
import java.util.Set;

/** Describes a detected switch statement. */
public record SwitchInfo(
        BasicBlock header,
        Map<Integer, Set<BasicBlock>> caseBodies,   // switchKey → body blocks
        Set<BasicBlock> defaultBody,
        boolean isTableSwitch
) {}
