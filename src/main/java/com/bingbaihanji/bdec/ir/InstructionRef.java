package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

/**
 * 指令引用记录.
 * <p>
 * 作为IR中指令结果的占位符,将一个IR指令及其结果类型绑定为值.
 * 实现{@link Value}接口,使得指令的结果可以作为其他指令的操作数进行链式引用.
 * </p>
 *
 * @param instruction 被引用的IR指令
 * @param type        该指令产生的结果类型
 */
public record InstructionRef(IrInstruction instruction, JavaType type) implements Value {
}
