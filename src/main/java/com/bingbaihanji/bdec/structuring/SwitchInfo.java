package com.bingbaihanji.bdec.structuring;

import com.bingbaihanji.bdec.cfg.BasicBlock;

import java.util.Map;
import java.util.Set;

/**
 * switch 语句的结构信息记录.
 *
 * @param header       switch 头块(以 tableswitch/lookupswitch 结尾的块)
 * @param caseBodies   switch 键值 → 对应 case 体块的映射
 * @param defaultBody  default 分支包含的基本块集合
 * @param isTableSwitch 是否为 tableswitch(true)还是 lookupswitch(false)
 */
public record SwitchInfo(
        BasicBlock header,
        Map<Integer, Set<BasicBlock>> caseBodies,
        Set<BasicBlock> defaultBody,
        boolean isTableSwitch
) {}
