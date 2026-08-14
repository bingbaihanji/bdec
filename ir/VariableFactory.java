package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;

/**
 * 局部变量工厂——从 {@link IrBuilder} 中提取的变量版本化与 LVT/LVTT
 * 名称/泛型/类型注解应用逻辑(里程碑 Phase 3).
 *
 * <p>每次 STORE 创建新版本变量,防止槽位混淆;LOAD 查找最新版本.
 * 所有方法均为无状态静态方法,接收方法模型作为显式参数.</p>
 */
final class VariableFactory {

    private VariableFactory() {}

    /**
     * 为STORE操作创建一个新版本的变量(槽位正在被写入).
     * 这防止槽位0('this')与存储到槽位0的临时变量相混淆.
     * 同时将isParameter标志从版本0传播到所有新版本,防止BlockReducer
     * 为参数重赋值(如"int dest = 0"遮盖了参数"int[] dest")生成VariableDeclaration.
     */
    static Variable createWriteVar(List<Variable> variables, int slot, JavaType type,
                                   int offset, MethodModel method) {
        int maxVersion = 0;
        boolean isParam = false;
        for (Variable v : variables) {
            if (v.slot() == slot) {
                maxVersion = Math.max(maxVersion, v.version());
                // 将isParameter从版本0传播到所有新版本.
                // 这防止BlockReducer为参数重赋值生成VariableDeclaration.
                if (v.isParameter() && v.version() == 0) {
                    isParam = true;
                }
            }
        }
        Variable v = new Variable(slot, maxVersion + 1, type, isParam, slot);
        // 向前传播局部变量表名称以便新版本保留原始参数名.
        // 但是跳过实例方法中的槽位0:'this'仅版本0有效;
        // 对槽位0的写入是一个重用该槽位的不同变量.
        // 使用作用域感知查找,无匹配时回退到 flat map(兼容无调试信息的 class)
        // 使用作用域感知查找.不退回 flat map,
        // 因为 last-wins 会在槽位复用时产生跨作用域的错误命名.
        String lvtName = method.lookupVarName(slot, offset);
        if (lvtName != null
                && !(slot == 0 && maxVersion > 0 && !method.isStatic())) {
            // slot 0 的保护仅适用于实例方法的 this 槽——
            // 静态方法的 slot 0 是普通局部变量(如 CycleTest.main 的 k)
            v.setName(lvtName);
        }
        // 泛型局部变量类型(LVTT):如 List<String> items
        // 按已解析的变量名对齐,防止槽位复用误取前一个变量的签名
        String lvttSig = method.lookupVarTypeSignature(slot, offset, lvtName);
        if (lvttSig != null) {
            try {
                JavaType gen = SignatureParser.parseGenericType(lvttSig);
                if (gen != null) {
                    v.setGenericType(gen);
                }
            } catch (Exception ignored) {
                // 签名解析失败——回退到擦除类型
            }
        }
        // 局部变量上的 JSR-308 类型注解(0x40/0x41):
        // 按 LVT 表索引 + (slot, pc 窗口, 名称) 对齐,防槽位复用误配
        List<TypeAnnotationEntry> varTypeAnns
                = method.lookupVarTypeAnnotations(slot, offset, v.name());
        if (!varTypeAnns.isEmpty()) {
            v.setTypeAnnotations(varTypeAnns);
        }
        variables.add(v);
        return v;
    }

    /**
     * 获取指定槽位最新版本的变量(用于LOAD指令).
     * 如果尚无该槽位的变量,则创建版本0的新变量,并应用局部变量表名称.
     */
    static Variable lookupReadVar(List<Variable> variables, int slot, JavaType type,
                                  int offset, MethodModel method) {
        Variable latest = null;
        for (Variable v : variables) {
            if (v.slot() == slot && (latest == null || v.version() > latest.version())) {
                latest = v;
            }
        }
        if (latest != null) {
            return latest;
        }
        // 首次访问:创建版本0.
        // 使用作用域感知查找.不退回 flat map 以免槽位复用时的跨作用域错误命名.
        Variable v = new Variable(slot, 0, type, false, slot);
        String lvtName = method.lookupVarName(slot, offset);
        if (lvtName != null) {
            v.setName(lvtName);
        }
        variables.add(v);
        return v;
    }
}
