package com.bingbaihanji.bdec.util;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.Map;

/**
 * 参数名解析器.
 *
 * <p>从 {@link MethodModel} 的局部变量表(LVT)中构建参数名数组,
 * 作为各层(AST 构建、枚举重写、lambda 重写)参数名解析的单一事实源.
 *
 * <p>槽位计算规则:非静态方法的槽位 0 是 {@code this},参数从槽位 1 开始;
 * 静态方法参数从槽位 0 开始.每个参数按其类型占用的槽位数
 * (long/double 占 2,void 占 0,其余占 1,见 {@link JavaType#slotCount()}) 前进.
 * 若某槽位在 LVT 中无(非空)名称,则回退为 {@code fallbackPrefix + i}.
 */
public final class ParameterNameResolver {

    private ParameterNameResolver() {}

    /**
     * 从方法模型的 LVT 构建参数名数组.
     *
     * @param method         方法模型
     * @param fallbackPrefix 无 LVT 名称时的回退前缀(如 {@code "param"},{@code "arg"})
     * @return 与 {@code method.parameterTypes()} 等长的参数名数组
     */
    public static String[] resolveNames(MethodModel method, String fallbackPrefix) {
        JavaType[] types = method.parameterTypes();
        String[] names = new String[types.length];
        Map<Integer, String> lvt = method.localVarNames();
        int slot = method.isStatic() ? 0 : 1;
        for (int i = 0; i < types.length; i++) {
            String name = lvt.get(slot);
            names[i] = (name != null && !name.isEmpty()) ? name : (fallbackPrefix + i);
            JavaType pt = types[i];
            slot += pt != null ? pt.slotCount() : 1;
        }
        return names;
    }
}
