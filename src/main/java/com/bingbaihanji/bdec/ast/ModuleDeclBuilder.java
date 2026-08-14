package com.bingbaihanji.bdec.ast;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.ModuleInfo;

import java.util.List;

/**
 * 模块声明构建器(里程碑 Phase 3).
 *
 * <p>将 {@link ClassFileModel} 的 Module 属性(JVMS 4.7.25)转换为 AST 的
 * {@link ModuleDeclaration} 节点。requires 的 transitive/static 修饰符
 * 按条目标志还原。</p>
 */
public final class ModuleDeclBuilder {

    private ModuleDeclBuilder() {
    }

    /**
     * 从 class 文件模型构建模块声明节点.
     *
     * @param classFile 已解析的 module-info class 文件模型
     * @return 模块声明节点
     */
    public static ModuleDeclaration build(ClassFileModel classFile) {
        ModuleInfo mi = classFile.moduleInfo();
        if (mi == null) {
            // 无 Module 属性(异常情况):输出空模块声明兜底
            return new ModuleDeclaration(
                    classFile.internalName(), false, null,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
        var requires = mi.requires().stream()
                .map(r -> new ModuleDeclaration.RequiresClause(
                        r.module(),
                        (r.flags() & ModuleInfo.RequiresEntry.ACC_TRANSITIVE) != 0,
                        (r.flags() & ModuleInfo.RequiresEntry.ACC_STATIC_PHASE) != 0))
                .toList();
        var exports = mi.exports().stream()
                .map(e -> new ModuleDeclaration.ExportsClause(
                        e.packageName(), e.toModules()))
                .toList();
        var opens = mi.opens().stream()
                .map(o -> new ModuleDeclaration.OpensClause(
                        o.packageName(), o.toModules()))
                .toList();
        var provides = mi.provides().stream()
                .map(p -> new ModuleDeclaration.ProvidesClause(
                        p.service(), p.withImplementations()))
                .toList();
        return new ModuleDeclaration(
                mi.name(),
                (mi.flags() & ModuleInfo.ACC_OPEN) != 0,
                mi.version(), requires, exports, opens, mi.uses(), provides);
    }
}
