package com.bingbaihanji.bdec.bytecode.parser;

import com.bingbaihanji.bdec.bytecode.model.ClassFileModel;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClassFileReaderTest {

    @Test
    public void testParseObjectClass() throws Exception {
        String javaHome = System.getProperty("java.home");
        Path objectPath = findObjectClass(javaHome);
        if (objectPath == null || !Files.exists(objectPath)) {
            System.out.println("Object.class not found at expected paths, skipping test");
            return;
        }

        byte[] bytes = Files.readAllBytes(objectPath);
        ClassFileReader reader = new ClassFileReader();
        ClassFileModel model = reader.read("java/lang/Object", bytes);

        assertEquals("java/lang/Object", model.internalName());
        assertNull("Object has no superclass", model.superInternalName());
        assertTrue("Major version >= 45", model.majorVersion() >= 45);
        assertTrue("Should have methods", model.methods().size() > 0);

        boolean hasHashCode = model.methods().stream()
                .anyMatch(m -> m.name().equals("hashCode") && m.descriptor().equals("()I"));
        assertTrue("Object should have hashCode()", hasHashCode);

        System.out.println("Parsed java.lang.Object v" + model.majorVersion()
                + ": " + model.fields().size() + " fields, " + model.methods().size() + " methods");
    }

    @Test
    public void testParseStringClass() throws Exception {
        String javaHome = System.getProperty("java.home");
        Path stringPath = findClassFile(javaHome, "java/lang/String.class");
        if (stringPath == null || !Files.exists(stringPath)) {
            System.out.println("String.class not found, skipping test");
            return;
        }

        byte[] bytes = Files.readAllBytes(stringPath);
        ClassFileReader reader = new ClassFileReader();
        ClassFileModel model = reader.read("java/lang/String", bytes);

        assertEquals("java/lang/String", model.internalName());
        assertTrue("String has many methods", model.methods().size() > 10);
        System.out.println("Parsed java.lang.String: " + model.methods().size() + " methods");
    }

    private Path findObjectClass(String javaHome) {
        Path p = Path.of(javaHome, "modules", "java.base", "java", "lang", "Object.class");
        if (Files.exists(p)) {
            return p;
        }
        return findClassFile(javaHome, "java/lang/Object.class");
    }

    private Path findClassFile(String javaHome, String relativePath) {
        Path modulesDir = Path.of(javaHome, "modules");
        if (Files.exists(modulesDir)) {
            try (var stream = Files.walk(modulesDir, 3)) {
                return stream.filter(p -> p.toString().endsWith(
                                relativePath.replace('/', java.io.File.separatorChar)))
                        .findFirst().orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
