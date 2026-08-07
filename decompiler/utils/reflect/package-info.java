/**
 * 反射访问,类路径扫描,虚拟文件系统和 JDK 访问限制处理工具包
 *
 * <p>应用场景:框架底层开发中的运行时类型反射,类路径下的资源扫描与加载(如自动发现
 * 注解类,配置文件等),绕过 JDK 模块系统的访问限制,通用对象/属性操作等需要动态
 * 操作类和字节码的场景 
 *
 * <ul>
 * <li>{@link ReflectUtil} —
 * 反射操作便捷工具(字段/方法/构造器访问)</li>
 * <li>{@link ClassUtil} —
 * 类加载,类型判断与类路径扫描</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.reflect.AccessPatcher} —
 * JDK 模块访问限制处理(--add-opens 等效操作)</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.reflect.VFS} /
 * {@link com.bingbaihanji.common.framework.utils.reflect.DefaultVFS} /
 * {@link com.bingbaihanji.common.framework.utils.reflect.JBoss6VFS} —
 * 虚拟文件系统抽象及实现</li>
 * </ul>
 *
 * @author 冰白寒祭
 * @since 2026-07-24
 */
package com.bingbaihanji.bdec.decompiler.utils.reflect;

import com.bingbaihanji.bdec.decompiler.utils.java.ClassUtil;