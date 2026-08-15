package com.bingbaihanji.bdec.ast.expr;

import com.bingbaihanji.bdec.ast.AstKind;
import com.bingbaihanji.bdec.ast.AstNode;
import com.bingbaihanji.bdec.ast.AstVisitor;
import com.bingbaihanji.bdec.type.JavaType;

import java.util.List;
import java.util.Map;

/**
 * 对象/数组创建表达式:{@code new 类型(参数)} 或 {@code new 类型[大小]}.
 * <p>
 * 表示Java中的对象实例化(调用构造函数)和数组创建操作.
 * 通过dimensions和constructorArgs两个字段区分数组创建和对象创建.
 * </p>
 */
public final class NewExpr extends Expression {

    /** 被实例化的类型 */
    private final JavaType instantiatedType;

    /** 数组维度表达式列表(数组创建时使用) */
    private final List<Expression> dimensions;

    /** 构造函数参数列表(对象创建时使用) */
    private final List<Expression> constructorArgs;

    /** 匿名类体成员列表(方法/字段声明,空列表表示非匿名类) */
    private final List<AstNode> anonymousBody;

    /** 数组初始化器元素(new T[]{a, b, c} 形式,空列表表示无初始化器) */
    private final List<Expression> arrayInitializer;

    /** JSR-308 类型注解(类型路径 → 渲染后注解行列表),来自 0x44/0x45 NEW 目标 */
    private final Map<List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
            List<String>> typeAnnotations;

    /**
     * 菱形推断标志:{@code true} 时发射 {@code new ArrayList<>(...)}.
     * 类型实参可被构造器实参或赋值目标类型推断时置位——字节码的 NEW 指令天然擦除,
     * 无法从字节码恢复源码的类型实参,但能证明"菱形推断可行"时输出 {@code <>},
     * 让 javac 在重编译时恢复泛型.
     */
    private final boolean diamond;

    /**
     * 构造对象/数组创建表达式.
     *
     * @param instantiatedType 被实例化的类型
     * @param dimensions       数组维度表达式
     * @param constructorArgs  构造函数参数
     */
    public NewExpr(JavaType instantiatedType, List<Expression> dimensions,
                   List<Expression> constructorArgs) {
        this(instantiatedType, dimensions, constructorArgs, List.of(), List.of());
    }

    /** 构造含数组初始化器的数组创建表达式 */
    public NewExpr(JavaType instantiatedType, List<Expression> dimensions,
                   List<Expression> constructorArgs, List<AstNode> anonymousBody,
                   List<Expression> arrayInitializer) {
        this(instantiatedType, dimensions, constructorArgs, anonymousBody,
                arrayInitializer, Map.of());
    }

    /**
     * 构造含 JSR-308 类型注解的对象/数组创建表达式.
     *
     * @param instantiatedType 被实例化的类型
     * @param dimensions       数组维度表达式
     * @param constructorArgs  构造函数参数
     * @param anonymousBody    匿名类体成员列表
     * @param arrayInitializer 数组初始化器元素
     * @param typeAnnotations  类型路径 → 渲染后注解行列表
     */
    public NewExpr(JavaType instantiatedType, List<Expression> dimensions,
                   List<Expression> constructorArgs, List<AstNode> anonymousBody,
                   List<Expression> arrayInitializer,
                   Map<List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                           List<String>> typeAnnotations) {
        this(instantiatedType, dimensions, constructorArgs, anonymousBody,
                arrayInitializer, typeAnnotations, false);
    }

    /** 全字段构造:含菱形推断标志. */
    public NewExpr(JavaType instantiatedType, List<Expression> dimensions,
                   List<Expression> constructorArgs, List<AstNode> anonymousBody,
                   List<Expression> arrayInitializer,
                   Map<List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
                           List<String>> typeAnnotations,
                   boolean diamond) {
        this.instantiatedType = instantiatedType;
        this.dimensions = List.copyOf(dimensions);
        this.constructorArgs = List.copyOf(constructorArgs);
        this.anonymousBody = List.copyOf(anonymousBody);
        this.arrayInitializer = List.copyOf(arrayInitializer);
        this.typeAnnotations = typeAnnotations == null ? Map.of() : typeAnnotations;
        this.diamond = diamond;
    }

    /**
     * 构造含匿名类体的对象创建表达式.
     *
     * @param instantiatedType 被实例化的类型(匿名类的父类/接口)
     * @param dimensions       数组维度表达式
     * @param constructorArgs  构造函数参数
     * @param anonymousBody    匿名类体成员列表
     */
    public NewExpr(JavaType instantiatedType, List<Expression> dimensions,
                   List<Expression> constructorArgs, List<AstNode> anonymousBody) {
        this(instantiatedType, dimensions, constructorArgs, anonymousBody, List.of());
    }

    /** @return 数组初始化器元素(空列表表示无初始化器) */
    public List<Expression> arrayInitializer() {return arrayInitializer;}

    /** @return 被实例化的类型 */
    public JavaType instantiatedType() {return instantiatedType;}

    /** @return 数组维度列表 */
    public List<Expression> dimensions() {return dimensions;}

    /** @return 构造函数参数列表 */
    public List<Expression> constructorArgs() {return constructorArgs;}

    /** @return 匿名类体成员列表(空列表表示非匿名类) */
    public List<AstNode> anonymousBody() {return anonymousBody;}

    /** @return 是否为匿名类实例化 */
    public boolean isAnonymousClass() {return !anonymousBody.isEmpty();}

    /** @return JSR-308 类型注解(类型路径 → 渲染后注解行列表) */
    public Map<List<com.bingbaihanji.bdec.bytecode.model.TypePathElement>,
            List<String>> typeAnnotations() {return typeAnnotations;}

    /** @return 是否以菱形 {@code <>} 发射(类型实参可推断) */
    public boolean diamond() {return diamond;}

    /** @return 置位菱形推断后的新节点(不改原节点). */
    public NewExpr withDiamond() {
        if (diamond) {
            return this;
        }
        return new NewExpr(instantiatedType, dimensions, constructorArgs,
                anonymousBody, arrayInitializer, typeAnnotations, true);
    }

    @Override
    public AstKind kind() {return AstKind.NEW;}

    @Override
    public List<AstNode> children() {
        if (!constructorArgs.isEmpty()) {
            return List.copyOf(constructorArgs);
        }
        if (!dimensions.isEmpty()) {
            return List.copyOf(dimensions);
        }
        return List.of();
    }

    @Override
    public int precedence() {return 13;}

    @Override
    public <R, C> R accept(AstVisitor<R, C> v, C c) {return v.visitExpression(this, c);}
}
