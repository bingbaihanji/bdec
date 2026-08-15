package com.bingbaihanji.bdec;

import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.structuring.IrreducibleHandler;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * 不可归约 CFG 检测:手工构造的多入口循环必须被判不可归约,正常循环可归约.
 */
public class IrreducibleCfgDetectionTest {

    @Test
    public void testDetectsIrreducibleCfg() throws Exception {
        byte[] bytes = IrreducibleClassGen.irrClassBytes();
        Path tmp = Files.createTempDirectory("bdec-irr-");
        Path cls = tmp.resolve("Irr.class");
        Files.write(cls, bytes);
        var model = new ClassFileReader().read(cls.toString(), bytes);
        boolean anyIrreducible = false;
        for (var method : model.methods()) {
            if ("<init>".equals(method.name())) {
                continue;
            }
            ControlFlowGraph cfg = new CfgBuilder().build(method);
            if (!IrreducibleHandler.isReducible(cfg)) {
                anyIrreducible = true;
            }
        }
        assertTrue("多入口循环应判定为不可归约", anyIrreducible);
    }

    @Test
    public void testReducibleCfg() throws Exception {
        String src = "class Reducible {\n"
                + "    static int sum(int n) {\n"
                + "        int s = 0;\n"
                + "        for (int i = 0; i < n; i++) { s += i; }\n"
                + "        return s;\n"
                + "    }\n"
                + "}\n";
        Path tmp = Files.createTempDirectory("bdec-red-");
        Path srcFile = tmp.resolve("Reducible.java");
        Files.writeString(srcFile, src, StandardCharsets.UTF_8);
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        int rc = c.run(null, null, null, "-g", "-d", tmp.toString(), srcFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("compile failed");
        }
        Path cls = tmp.resolve("Reducible.class");
        var model = new ClassFileReader().read(cls.toString(),
                Files.readAllBytes(cls));
        for (var method : model.methods()) {
            if ("<init>".equals(method.name())) {
                continue;
            }
            ControlFlowGraph cfg = new CfgBuilder().build(method);
            assertTrue("正常 for 循环应可归约: " + method.name(),
                    IrreducibleHandler.isReducible(cfg));
        }
    }
}
