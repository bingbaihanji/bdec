package com.bingbaihanji.bdec;

/**
 * 构建信息:引擎名称与版本号的唯一事实源.
 *
 * <p>此前版本号散落在 {@link BdecEngine},{@link BdecCli},
 * {@code decompiler.Decompiler} 接口与 pom.xml 四处且互相矛盾
 * (0.1.0 / 0.1.0 / 1.0.0 / 1.0-SNAPSHOT),统一至此以避免漂移.
 * 版本号与 pom.xml 的 {@code <version>} 保持一致.</p>
 */
public final class BuildInfo {

    /** 引擎名称. */
    public static final String NAME = "bdec";

    /** 语义化版本号. */
    public static final String VERSION = "1.0.0";

    private BuildInfo() {
    }
}
