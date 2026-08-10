package com.bingbaihanji.bdec.ast.rewrite;

import com.bingbaihanji.bdec.DecompileContext;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.CompilationUnit;
import com.bingbaihanji.bdec.ast.TypeDeclaration;
import com.bingbaihanji.bdec.ast.expr.Expression;
import com.bingbaihanji.bdec.ast.expr.InvocationExpr;
import com.bingbaihanji.bdec.ast.stmt.BlockStatement;
import com.bingbaihanji.bdec.ast.stmt.ExpressionStatement;
import com.bingbaihanji.bdec.ast.stmt.FieldDeclaration;
import com.bingbaihanji.bdec.ast.stmt.MethodDeclaration;
import com.bingbaihanji.bdec.ast.stmt.Statement;
import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import com.bingbaihanji.bdec.bytecode.model.Instruction;
import com.bingbaihanji.bdec.bytecode.model.MethodModel;
import com.bingbaihanji.bdec.bytecode.model.constantpool.ConstantPoolEntry;
import com.bingbaihanji.bdec.bytecode.model.constantpool.InnerClassEntry;
import com.bingbaihanji.bdec.bytecode.parser.ClassFileReader;
import com.bingbaihanji.bdec.bytecode.parser.ConstantPoolParser;
import com.bingbaihanji.bdec.cfg.CfgBuilder;
import com.bingbaihanji.bdec.cfg.ControlFlowGraph;
import com.bingbaihanji.bdec.ir.IrBuilder;
import com.bingbaihanji.bdec.ir.LinearIr;
import com.bingbaihanji.bdec.semantic.SemanticReconstructor;
import com.bingbaihanji.bdec.structuring.ControlFlowStructurer;
import com.bingbaihanji.bdec.structuring.StructuredMethod;
import com.bingbaihanji.bdec.type.JavaType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects enum classes and restores the {@code enum} keyword,
 * removing synthetic {@code $VALUES}, {@code values()}, and
 * {@code valueOf(String)} members.
 *
 * <p>Inspired by Vineflower's {@code EnumProcessor}.
 */
public class EnumRewriter implements RewriteRule {

    /** ACC_ENUM flag (0x4000). */
    private static final int ACC_ENUM = 0x4000;

    // JVM opcodes for constant-pushing instructions
    private static final int OP_ICONST_M1 = 2;
    private static final int OP_ICONST_0 = 3;
    private static final int OP_ICONST_1 = 4;
    private static final int OP_ICONST_2 = 5;
    private static final int OP_ICONST_3 = 6;
    private static final int OP_ICONST_4 = 7;
    private static final int OP_ICONST_5 = 8;
    private static final int OP_BIPUSH = 16;
    private static final int OP_SIPUSH = 17;
    private static final int OP_LDC = 18;
    private static final int OP_LDC_W = 19;
    private static final int OP_LDC2_W = 20;
    private static final int OP_NEW = 187;
    private static final int OP_DUP = 89;
    private static final int OP_INVOKESPECIAL = 183;
    private static final int OP_PUTSTATIC = 179;

    @Override
    public String name() {return "enum";}

    @Override
    public CompilationUnit rewrite(CompilationUnit unit, DecompileContext context) {
        List<TypeDeclaration> types = new ArrayList<>();
        for (TypeDeclaration td : unit.types()) {
            types.add(rewriteType(td, context));
        }
        return new CompilationUnit(unit.packageName(), unit.imports(), types);
    }

    private TypeDeclaration rewriteType(TypeDeclaration td, DecompileContext ctx) {
        if (!isEnum(td)) {
            return td;
        }

        // Extract enum constant constructor args from <clinit> bytecode.
        Map<String, String> constArgs = extractConstantArgs(td, ctx);

        // Check if this enum has abstract methods (needs anonymous class bodies).
        boolean hasAbstract = hasAbstractMethods(td);

        // Separate enum constant fields from other members
        List<String> enumConstants = new ArrayList<>();
        List<AstNode> regularMembers = new ArrayList<>();

        for (AstNode m : td.children()) {
            if (m instanceof FieldDeclaration fd) {
                if (isValuesField(fd)) {
                    continue;
                }
                if (isEnumConstantField(fd, td.simpleName())) {
                    String args = constArgs.getOrDefault(fd.name(), "");
                    String body = "";
                    if (hasAbstract) {
                        // Try to load inner class for this enum constant's
                        // anonymous class body (e.g., EnumDemo$1 for ordinal 0).
                        body = buildAnonymousClassBody(fd.name(), args,
                                td.simpleName(), enumConstants.size(), ctx);
                    }
                    enumConstants.add(fd.name() + args + body);
                    continue;
                }
            }
            if (m instanceof MethodDeclaration md) {
                if (isEnumSyntheticMethod(md)) {
                    continue;
                }
                if (isEnumStaticInit(md)) {
                    continue; // stripped — replaced by enum constant list
                }
                if (isEnumConstructor(md, td.simpleName())) {
                    regularMembers.add(fixEnumConstructor(md));
                    continue;
                }
            }
            regularMembers.add(m);
        }

        // Emit enum constants as a special field marker.
        List<AstNode> members = new ArrayList<>();
        if (!enumConstants.isEmpty()) {
            String constList = String.join(",\n    ", enumConstants)
                    + (enumConstants.size() == 1 ? ";" : "\n    ;");
            members.add(new FieldDeclaration(0, "$enumConstants$",
                    com.bingbaihanji.bdec.type.JavaType.VOID,
                    new com.bingbaihanji.bdec.ast.expr.VarExpr(constList)));
        }
        members.addAll(regularMembers);

        int flags = (td.accessFlags() & ~(ACC_ENUM | 0x0400));
        return new TypeDeclaration(flags, td.simpleName(), "enum", null,
                td.interfaceNames(), td.typeParameters(), members);
    }

    /**
     * Extract constructor arguments for each enum constant by parsing
     * the {@code <clinit>} bytecode. Returns a map of field name to
     * argument source text (e.g., {@code "(1)"} or {@code "(\"foo\", 42)"}).
     */
    private Map<String, String> extractConstantArgs(TypeDeclaration td,
                                                     DecompileContext ctx) {
        Map<String, String> result = new HashMap<>();
        ClassFileModel cfm = ctx.classFile();
        if (cfm == null) {
            return result;
        }

        // Find the <clinit> method
        MethodModel clinit = null;
        for (MethodModel m : cfm.methods()) {
            if ("<clinit>".equals(m.name())) {
                clinit = m;
                break;
            }
        }
        if (clinit == null || clinit.instructions() == null) {
            return result;
        }

        List<Instruction> insns = clinit.instructions();
        ConstantPoolEntry[] pool = cfm.constantPool();

        // Scan for enum constant creation patterns.
        for (int i = 0; i < insns.size(); i++) {
            Instruction insn = insns.get(i);
            if (insn.opcode() != OP_NEW) {
                continue;
            }
            // Expect DUP next
            if (i + 1 >= insns.size() || insns.get(i + 1).opcode() != OP_DUP) {
                continue;
            }

            // Collect pushed args between DUP and INVOKESPECIAL
            int argStart = i + 2;
            List<String> argTexts = new ArrayList<>();
            int j = argStart;
            while (j < insns.size() && insns.get(j).opcode() != OP_INVOKESPECIAL) {
                String argText = pushValueToText(insns.get(j), pool);
                if (argText != null) {
                    argTexts.add(argText);
                }
                j++;
            }

            // Expect INVOKESPECIAL followed by PUTSTATIC
            if (j >= insns.size() || insns.get(j).opcode() != OP_INVOKESPECIAL) {
                continue;
            }
            if (j + 1 >= insns.size() || insns.get(j + 1).opcode() != OP_PUTSTATIC) {
                continue;
            }

            // Get field name from PUTSTATIC
            Instruction putstatic = insns.get(j + 1);
            String fieldName = fieldNameFromPutstatic(putstatic, pool);

            // Skip synthetic args (name string and ordinal int)
            // User args start at index 2
            if (argTexts.size() > 2 && fieldName != null) {
                List<String> userArgs = argTexts.subList(2, argTexts.size());
                result.put(fieldName, "(" + String.join(", ", userArgs) + ")");
            }

            i = j + 1; // advance past this pattern
        }

        return result;
    }

    /** Convert a push instruction to its source text representation.
     *  Handles ICONST, BIPUSH, SIPUSH, LDC, LDC_W, and LDC2_W. */
    private static String pushValueToText(Instruction insn, ConstantPoolEntry[] pool) {
        int op = insn.opcode();
        // ICONST_M1 .. ICONST_5
        if (op >= OP_ICONST_M1 && op <= OP_ICONST_5) {
            int val = op - OP_ICONST_0;
            return String.valueOf(val);
        }
        // BIPUSH — operand is a single signed byte
        if (op == OP_BIPUSH && !insn.rawOperands().isEmpty()) {
            int val = (byte) insn.rawOperands().get(0).intValue();
            return String.valueOf(val);
        }
        // SIPUSH — operand is a single signed short (16-bit, decoded as int)
        if (op == OP_SIPUSH && !insn.rawOperands().isEmpty()) {
            int val = (short) insn.rawOperands().get(0).intValue();
            return String.valueOf(val);
        }
        // LDC or LDC_W — pool index in rawOperands[0]
        if ((op == OP_LDC || op == OP_LDC_W) && !insn.rawOperands().isEmpty()) {
            return ldcValueToText(insn.rawOperands().get(0), pool);
        }
        // LDC2_W — pool index in rawOperands[0] (long or double)
        if (op == OP_LDC2_W && !insn.rawOperands().isEmpty()) {
            return ldcValueToText(insn.rawOperands().get(0), pool);
        }
        return null;
    }

    /** Look up a constant pool entry and convert it to Java source text. */
    private static String ldcValueToText(int poolIdx, ConstantPoolEntry[] pool) {
        ConstantPoolEntry entry = pool[poolIdx];
        if (entry instanceof ConstantPoolEntry.CpInteger cpi) {
            return String.valueOf(cpi.value());
        }
        if (entry instanceof ConstantPoolEntry.CpString cps) {
            return "\"" + ConstantPoolParser.utf8(pool, cps.stringIndex()) + "\"";
        }
        if (entry instanceof ConstantPoolEntry.CpFloat cpf) {
            return String.valueOf(cpf.value()) + "F";
        }
        if (entry instanceof ConstantPoolEntry.CpLong cpl) {
            return String.valueOf(cpl.value()) + "L";
        }
        if (entry instanceof ConstantPoolEntry.CpDouble cpd) {
            return String.valueOf(cpd.value()) + "D";
        }
        if (entry instanceof ConstantPoolEntry.CpClass cpc) {
            return ConstantPoolParser.utf8(pool, cpc.nameIndex())
                    .replace('/', '.') + ".class";
        }
        return null;
    }

    /** Extract field name from a PUTSTATIC instruction.
     *  PUTSTATIC has a 2-byte pool index operand encoded as a single int
     *  from {@code readUnsignedShort()} in the instruction decoder. */
    private static String fieldNameFromPutstatic(Instruction insn,
                                                   ConstantPoolEntry[] pool) {
        if (insn.rawOperands().isEmpty()) {
            return null;
        }
        int poolIdx = insn.rawOperands().get(0);
        ConstantPoolEntry entry = pool[poolIdx];
        if (entry instanceof ConstantPoolEntry.CpFieldRef fr) {
            ConstantPoolEntry nat = pool[fr.nameAndTypeIndex()];
            if (nat instanceof ConstantPoolEntry.CpNameAndType nt) {
                return ConstantPoolParser.utf8(pool, nt.nameIndex());
            }
        }
        return null;
    }

    /** Check if a field is an enum constant (public static final of the enum type). */
    private boolean isEnumConstantField(FieldDeclaration fd, String enumName) {
        int flags = fd.accessFlags();
        boolean isPublicStaticFinal = (flags & 0x0019) == 0x0019;
        if (!isPublicStaticFinal) {
            return false;
        }
        String typeStr = fd.type() != null ? fd.type().displayName() : "";
        return typeStr.contains(enumName);
    }

    private boolean isEnum(TypeDeclaration td) {
        return (td.accessFlags() & ACC_ENUM) != 0;
    }

    private boolean isValuesField(FieldDeclaration fd) {
        return "$VALUES".equals(fd.name());
    }

    private boolean isEnumSyntheticMethod(MethodDeclaration md) {
        String name = md.name();
        if ("values".equals(name) || name != null && name.startsWith("$values")) {
            return md.parameterNames().length == 0 && md.isStatic();
        }
        if ("valueOf".equals(name)) {
            return md.parameterNames().length == 1 && md.isStatic();
        }
        return false;
    }

    private boolean isEnumStaticInit(MethodDeclaration md) {
        return md.name() == null;
    }

    private boolean isEnumConstructor(MethodDeclaration md, String enumName) {
        if (!enumName.equals(md.name()) && !"<init>".equals(md.name())) {
            return false;
        }
        return md.parameterNames().length >= 2;
    }

    private MethodDeclaration fixEnumConstructor(MethodDeclaration md) {
        int origLen = md.parameterNames().length;
        if (origLen < 2) {
            return md;
        }
        int newLen = origLen - 2;
        String[] newNames = new String[newLen];
        com.bingbaihanji.bdec.type.JavaType[] newTypes = new com.bingbaihanji.bdec.type.JavaType[newLen];
        System.arraycopy(md.parameterNames(), 2, newNames, 0, newLen);
        System.arraycopy(md.parameterTypes(), 2, newTypes, 0, newLen);

        Statement body = md.body() != null ? cleanEnumConstructorBody(md.body()) : null;

        return new MethodDeclaration(md.accessFlags(), md.name(), md.returnType(),
                newNames, newTypes, md.typeParameters(), body);
    }

    private Statement cleanEnumConstructorBody(Statement body) {
        if (!(body instanceof BlockStatement bs)) {
            return body;
        }
        List<Statement> filtered = new ArrayList<>();
        for (Statement s : bs.statements()) {
            if (s instanceof com.bingbaihanji.bdec.ast.stmt.VariableDeclaration vd
                    && vd.name().startsWith("var")
                    && vd.initializer() != null
                    && vd.initializer() instanceof com.bingbaihanji.bdec.ast.expr.LitExpr l
                    && l.value() instanceof Integer i && i == 0) {
                continue;
            }
            if (s instanceof ExpressionStatement es
                    && es.expression() instanceof InvocationExpr inv
                    && "super".equals(inv.methodName())) {
                continue;
            }
            filtered.add(s);
        }
        if (filtered.isEmpty()) {
            return new BlockStatement(List.of());
        }
        return new BlockStatement(filtered);
    }

    // ── Anonymous class body support ─────────────────────────────────

    /** Check if the enum has abstract methods that need anonymous class
     *  bodies on enum constants. */
    private static boolean hasAbstractMethods(TypeDeclaration td) {
        for (AstNode m : td.children()) {
            if (m instanceof MethodDeclaration md
                    && (md.accessFlags() & 0x0400) != 0) { // ACC_ABSTRACT
                return true;
            }
        }
        return false;
    }

    /** Build anonymous class body source text for an enum constant
     *  by loading and decompiling its inner class
     *  (e.g., {@code EnumDemo$1} for ordinal 0). */
    private String buildAnonymousClassBody(String constName, String args,
                                            String enumName, int ordinal,
                                            DecompileContext ctx) {
        String innerName = enumName + "$" + (ordinal + 1);

        // Build full internal name from the enum's package
        ClassFileModel cfm = ctx.classFile();
        String pkg = cfm != null ? packageOf(cfm.internalName()) : "";
        String internalName = pkg.isEmpty() ? innerName : pkg + "/" + innerName;

        byte[] bytes = ctx.loadClassBytes(internalName);
        if (bytes == null) {
            return "";
        }

        try {
            ClassFileModel inner = new ClassFileReader().read(internalName, bytes);
            String bodies = decompileInnerClassMethods(inner, ctx);
            return bodies;
        } catch (IOException e) {
            return "";
        }
    }

    private static String packageOf(String internalName) {
        int idx = internalName.lastIndexOf('/');
        return idx >= 0 ? internalName.substring(0, idx) : "";
    }

    /** Decompile the non-constructor methods of an inner enum constant
     *  class and return their source text. */
    private static String decompileInnerClassMethods(ClassFileModel inner,
                                                      DecompileContext ctx) {
        CfgBuilder cfgBuilder = new CfgBuilder();
        IrBuilder irBuilder = new IrBuilder();
        SemanticReconstructor sr = new SemanticReconstructor();
        ControlFlowStructurer structurer = new ControlFlowStructurer();

        List<String> methodSources = new ArrayList<>();
        for (MethodModel method : inner.methods()) {
            if ("<init>".equals(method.name())) {
                continue;
            }
            if ("<clinit>".equals(method.name())) {
                continue;
            }
            if (method.isAbstract() || method.isNative()) {
                continue;
            }

            try {
                ControlFlowGraph cfg = cfgBuilder.build(method);
                LinearIr ir = irBuilder.build(cfg, method,
                        inner.constantPool(), inner.bootstrapMethods());
                ir = sr.reconstruct(ir, method, cfg, inner);
                StructuredMethod sm = structurer.structure(ir, ctx);

                if (sm.body() == null) {
                    continue;
                }

                // Build parameter names and types from the method model.
                String[] paramNames = buildParamNames(method);
                JavaType[] paramTypes = method.parameterTypes();

                MethodDeclaration md = new MethodDeclaration(
                        method.accessFlags(),
                        method.name(),
                        method.returnType(),
                        paramNames,
                        paramTypes,
                        List.of(),
                        sm.body()
                );

                // Emit the single method to a source string.
                String src = emitSingleMethod(md);
                if (src != null && !src.isEmpty()) {
                    methodSources.add(src);
                }
            } catch (Exception e) {
                // Method decompilation failed — skip this method.
            }
        }

        if (methodSources.isEmpty()) {
            return "";
        }

        // Build the anonymous class body: { method1 method2 ... }
        StringBuilder sb = new StringBuilder();
        sb.append(" {\n");
        for (String ms : methodSources) {
            for (String line : ms.split("\n")) {
                sb.append("        ").append(line).append("\n");
            }
        }
        sb.append("    }");
        return sb.toString();
    }

    /** Build parameter names from the method model's local variable table
     *  or fall back to synthetic names. */
    private static String[] buildParamNames(MethodModel method) {
        int count = method.parameterTypes() != null
                ? method.parameterTypes().length : 0;
        String[] names = new String[count];
        Map<Integer, String> lvt = method.localVarNames();
        // Parameters occupy slots starting from 0 (or 1 for instance methods).
        int slot = method.isStatic() ? 0 : 1;
        for (int i = 0; i < count; i++) {
            String name = lvt != null ? lvt.get(slot) : null;
            names[i] = (name != null) ? name : ("param" + i);
            JavaType pt = method.parameterTypes() != null
                    && i < method.parameterTypes().length
                    ? method.parameterTypes()[i] : JavaType.classType("java/lang/Object");
            slot += pt.slotCount();
        }
        return names;
    }

    /** Emit a single MethodDeclaration to a source string using
     *  StatementEmitter with a temporary IndentWriter. */
    private static String emitSingleMethod(MethodDeclaration md) {
        com.bingbaihanji.bdec.emit.IndentWriter w =
                new com.bingbaihanji.bdec.emit.IndentWriter(4);
        com.bingbaihanji.bdec.emit.ExpressionEmitter exprs =
                new com.bingbaihanji.bdec.emit.ExpressionEmitter(w, List.of());
        com.bingbaihanji.bdec.emit.StatementEmitter stmts =
                new com.bingbaihanji.bdec.emit.StatementEmitter(w, exprs,
                        "Enum", false);
        stmts.emit(md);
        return w.toString().trim();
    }
}
