package com.bingbaihanji.bdec.decompiler.utils.reflect;


import com.bingbaihanji.bdec.decompiler.utils.collection.ArraySet;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 解除 JDK 内部反射访问限制的工具
 * <p>
 * <b>必须先初始化 {@link ReflectUtil}!</b>
 *
 * @author xDark
 */
public final class AccessPatcher {


    private static volatile boolean patched;

    // 禁止任何实例化
    private AccessPatcher() {
    }

    /**
     * 修补 JDK 访问限制
     */
    public static void patch() {
        if (patched) {
            return;
        }
        synchronized (AccessPatcher.class) {
            if (patched) {
                return;
            }
            try {
                openPackages();
                patchReflectionFilters();
                patched = true;
                // log.debug("Access patching completed successfully");
            } catch (Throwable t) {
                // log.error("Failed access patching - some reflection operations may be restricted", t);
            }
        }
    }

    /**
     * 开放所有包
     */
    private static void openPackages() {
        try {
            Class<?> context = AccessPatcher.class;
            Set<Module> modules = new ArraySet<>();
            Module base = context.getModule();
            ModuleLayer baseLayer = base.getLayer();
            if (baseLayer != null) {
                modules.addAll(baseLayer.modules());
            }
            modules.addAll(ModuleLayer.boot().modules());
            for (ClassLoader cl = context.getClassLoader(); cl != null; cl = cl.getParent()) {
                modules.add(cl.getUnnamedModule());
            }
            MethodHandle export = ReflectUtil.lookup()
                    .findVirtual(Module.class, "implAddOpens", MethodType.methodType(void.class, String.class));
            for (Module module : modules) {
                for (String name : module.getPackages()) {
                    try {
                        export.invokeExact(module, name);
                    } catch (Exception ex) {
                        // log.error("Could not export package {} in module {}", name, module);
                        // log.error("Root cause: ", ex);
                    }
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Could not export packages", t);
        }
    }

    /**
     * 修补反射过滤器
     */
    private static void patchReflectionFilters() {
        Class<?> klass;
        try {
            klass = Class.forName("jdk.internal.reflect.Reflection", true, null);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Unable to locate 'jdk.internal.reflect.Reflection' class", ex);
        }
        try {
            MethodHandles.Lookup lookup = ReflectUtil.lookup();
            lookup.findStaticSetter(klass, "fieldFilterMap", Map.class).invokeExact((Map<?, ?>) new HashMap<>());
            lookup.findStaticSetter(klass, "methodFilterMap", Map.class).invokeExact((Map<?, ?>) new HashMap<>());
        } catch (Throwable t) {
            throw new IllegalStateException("Unable to patch reflection filters", t);
        }
    }

}
