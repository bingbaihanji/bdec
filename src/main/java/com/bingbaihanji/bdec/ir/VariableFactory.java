package com.bingbaihanji.bdec.ir;

import com.bingbaihanji.bdec.bytecode.model.LocalVariableEntry;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.TypeAnnotationEntry;
import com.bingbaihanji.bdec.bytecode.parser.SignatureParser;
import com.bingbaihanji.bdec.type.JavaType;
import com.bingbaihanji.bdec.type.TypeResolver;

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
        // 解析 LVT 条目(作用域感知,与 lookupVarName 相同的选择规则),
        // 使变量名与声明类型来自作用域一致的条目,避免槽位复用时的跨作用域错配.
        // 名称允许回退到已结束作用域的最近条目(仅作为命名兜底),
        // 但声明类型只用"确切覆盖或窗口匹配"的条目——回退匹配可能命中
        // 已结束作用域的前一个变量(如 instanceof 模式变量 s 与 switch 判别式
        // 复用同一槽位),其类型会把 {@code Object s = o} 误收窄成 {@code String s = o}.
        LocalVariableEntry lvtEntry = lookupVarEntry(method, slot, offset);
        LocalVariableEntry typeEntry = lookupTypeEntry(method, slot, offset);
        Variable v = new Variable(slot, maxVersion + 1,
                lookupDeclaredType(typeEntry, type), isParam, slot);
        // 向前传播局部变量表名称以便新版本保留原始参数名.
        // 但是跳过实例方法中的槽位0:'this'仅版本0有效;
        // 对槽位0的写入是一个重用该槽位的不同变量.
        // 使用作用域感知查找.不退回 flat map,
        // 因为 last-wins 会在槽位复用时产生跨作用域的错误命名.
        String lvtName = lvtEntry != null ? lvtEntry.name() : null;
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

    /**
     * 按 STORE 偏移量查找槽位对应的 LVT 条目(作用域感知).
     *
     * <p>选择规则与 {@link MethodModel#lookupVarName} 完全一致:
     * 先精确作用域覆盖,再 (pc, pc+4] 窗口(条目 startPc 指向 STORE 之后),
     * 最后回退到 startPc<=pc 的最近条目.返回的条目同时携带变量名与
     * 声明类型描述符,保证名称与类型来自同一条目,槽位复用时不串味.</p>
     */
    private static LocalVariableEntry lookupVarEntry(MethodModel method, int slot, int pc) {
        for (LocalVariableEntry entry : method.localVarEntries()) {
            if (entry.slot() == slot && entry.covers(pc)) {
                return entry;
            }
        }
        LocalVariableEntry windowBest = null;
        for (LocalVariableEntry entry : method.localVarEntries()) {
            if (entry.slot() == slot && entry.startPc() > pc && entry.startPc() <= pc + 4
                    && (windowBest == null || entry.startPc() < windowBest.startPc())) {
                windowBest = entry;
            }
        }
        if (windowBest != null) {
            return windowBest;
        }
        LocalVariableEntry best = null;
        for (LocalVariableEntry entry : method.localVarEntries()) {
            if (entry.slot() == slot && entry.startPc() <= pc
                    && (best == null || entry.startPc() > best.startPc())) {
                best = entry;
            }
        }
        return best;
    }

    /**
     * 按 STORE 偏移量查找槽位对应 LVT 条目中"可靠的声明类型"来源.
     *
     * <p>仅接受两种可靠匹配(与 {@link #lookupVarEntry} 的前两阶段相同):
     * <ul>
     *   <li>确切作用域覆盖:条目 {@code covers(pc)},变量当前就在作用域内;</li>
     *   <li>(pc, pc+4] 窗口:条目 startPc 指向 STORE 之后的首条指令
     *       (JVMS 规定),即本次 STORE 正是该变量的首次定义.</li>
     * </ul>
     * 不采用 {@code startPc <= pc} 的回退——槽位复用时回退可能命中
     * 已结束作用域的前一个变量,其声明类型不是本次写入变量的类型
     * (例如 instanceof 模式变量 s 与 switch 判别式共享槽位).</p>
     */
    private static LocalVariableEntry lookupTypeEntry(MethodModel method, int slot, int pc) {
        for (LocalVariableEntry entry : method.localVarEntries()) {
            if (entry.slot() == slot && entry.covers(pc)) {
                return entry;
            }
        }
        LocalVariableEntry windowBest = null;
        for (LocalVariableEntry entry : method.localVarEntries()) {
            if (entry.slot() == slot && entry.startPc() > pc && entry.startPc() <= pc + 4
                    && (windowBest == null || entry.startPc() < windowBest.startPc())) {
                windowBest = entry;
            }
        }
        return windowBest;
    }

    /**
     * 从 LVT 条目解析变量的声明类型描述符(LocalVariableTable 的 typeDesc).
     *
     * <p>有 LVT 时优先采用 LVT 声明的类型——修复"变量声明类型收窄":
     * {@code Object o = new ArrayList()} 此前用被存值 {@code ArrayList} 作为
     * 变量类型,现改为 LVT 声明的 {@code Object}.无 LVT(无调试信息)或
     * 描述符解析失败时回退到被存值类型 {@code fallback}.</p>
     */
    private static JavaType lookupDeclaredType(LocalVariableEntry entry, JavaType fallback) {
        if (entry == null || entry.typeDesc() == null) {
            return fallback;
        }
        try {
            JavaType declared = TypeResolver.parseFieldType(entry.typeDesc());
            return declared != null ? declared : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * 供 {@link IrBuilder#handleStore} 使用:按偏移量解析槽位在 LVT 中声明的类型,
     * 无可靠匹配条目时回退 {@code fallback}.与 {@code createWriteVar} 内部使用的
     * 类型选择规则一致(同一 {@code lookupTypeEntry} 选择逻辑).
     */
    static JavaType lookupDeclaredType(MethodModel method, int slot, int pc, JavaType fallback) {
        return lookupDeclaredType(lookupTypeEntry(method, slot, pc), fallback);
    }
}
