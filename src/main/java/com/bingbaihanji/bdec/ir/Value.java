package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.type.JavaType;

/**
 * 值接口.
 * <p>
 * IR中所有可操作值的基类型,被{@link Variable},{@link ConstantValue},
 * {@link InstructionRef}三个实现类封闭继承.
 * 所有值都必须提供其Java类型信息.
 * </p>
 */
public sealed interface Value permits Variable, ConstantValue, InstructionRef {

    /**
     * 获取该值的Java类型.
     *
     * @return Java类型
     */
    JavaType type();
}
