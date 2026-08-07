package com.bingbaihanji.bdec.emit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImportManager {

    private final String currentPackage;

    private final Map<String, String> importedNames = new LinkedHashMap<>();

    public ImportManager(String currentPackage) {
        this.currentPackage = currentPackage != null ? currentPackage : "";
    }

    private static int priority(String s) {
        if (s.startsWith("import java.")) {
            return 0;
        }
        if (s.startsWith("import javax.")) {
            return 1;
        }
        return 2;
    }

    public String registerType(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return qualifiedName;
        }
        if (isPrimitive(qualifiedName)) {
            return qualifiedName;
        }

        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            return qualifiedName;
        }
        String pkg = qualifiedName.substring(0, lastDot);
        String shortName = qualifiedName.substring(lastDot + 1);

        if ("java.lang".equals(pkg)) {
            return shortName;
        }
        if (pkg.equals(currentPackage)) {
            return shortName;
        }

        String existing = importedNames.get(shortName);
        if (existing != null && !existing.equals(qualifiedName)) {
            return qualifiedName; // conflict — use fully qualified
        }
        importedNames.putIfAbsent(shortName, qualifiedName);
        return shortName;
    }

    public List<String> finalizeImports() {
        List<String> result = new ArrayList<>();
        for (var e : importedNames.entrySet()) {
            String pkg = e.getValue().substring(0, e.getValue().lastIndexOf('.'));
            if (!"java.lang".equals(pkg) && !pkg.equals(currentPackage)) {
                result.add("import " + e.getValue() + ";");
            }
        }
        result.sort(Comparator.comparingInt(ImportManager::priority).thenComparing(s -> s));
        return result;
    }

    private boolean isPrimitive(String s) {
        return switch (s) {
            case "void", "boolean", "byte", "short", "char", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }
}
