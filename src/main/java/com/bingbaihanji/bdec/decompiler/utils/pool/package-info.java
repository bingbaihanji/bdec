/**
 * 对象池工具包
 *
 * <p>应用场景:高并发下的对象复用(减少 GC 压力),连接池,线程池内的资源缓存,
 * 大对象或创建成本高的对象的生命周期管理等需要控制资源分配的场景 
 *
 * <ul>
 * <li>{@link com.bingbaihanji.common.framework.utils.pool.ObjectPool} — 通用对象池</li>
 * <li>{@link com.bingbaihanji.common.framework.utils.pool.FixedSizePool} — 固定大小对象池</li>
 * </ul>
 *
 * @author 冰白寒祭
 * @since 2026-07-24
 */
package com.bingbaihanji.bdec.decompiler.utils.pool;
