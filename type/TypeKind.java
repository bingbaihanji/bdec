package com.bingbaihanji.bdec.type;

/**
 * 类型种类枚举,涵盖 Java 中的所有类型分类.
 * 包括基本类型(8 种),类/接口类型,数组类型,
 * 泛型类型变量,通配符类型和方法类型.
 */
public enum TypeKind {
    /** void 类型 */
    VOID,
    /** boolean 基本类型 */
    BOOLEAN,
    /** byte 基本类型 */
    BYTE,
    /** short 基本类型 */
    SHORT,
    /** char 基本类型 */
    CHAR,
    /** int 基本类型 */
    INT,
    /** long 基本类型 */
    LONG,
    /** float 基本类型 */
    FLOAT,
    /** double 基本类型 */
    DOUBLE,
    /** 类或接口引用类型 */
    CLASS,
    /** 数组类型 */
    ARRAY,
    /** 泛型类型变量(如 T,E 等) */
    TYPE_VARIABLE,
    /** 泛型通配符类型(如 ?,? extends X,? super Y) */
    WILDCARD,
    /** 方法类型(用于 Lambda 或方法引用) */
    METHOD_TYPE
}
