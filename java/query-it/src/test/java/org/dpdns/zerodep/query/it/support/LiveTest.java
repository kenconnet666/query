package org.dpdns.zerodep.query.it.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 实库测试类：在每个选中的库上各跑一遍整个类，报告里显示成 {@code [MySQL] 测试名}。类里用 {@code @Parameter Live live}
 * 拿到当前库的上下文（见 {@link Live}，选库的规则也写在那里）。
 *
 * <pre>{@code
 * @LiveTest                            // 全部的库
 * @LiveTest(only = {MYSQL, POLARDB})   // 只在这几个库上
 * @LiveTest(except = SQLITE)           // 除了 SQLite
 * }</pre>
 *
 * 测试类自己的表在 {@code @BeforeParameterizedClassInvocation static void tables(Live live)} 里建，每个库建一次。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedClass(name = "{0}", allowZeroInvocations = true)
@ArgumentsSource(LiveTargets.class)
@ExtendWith(LiveTargets.class)
public @interface LiveTest {
    /** 只在这些库上运行；为空表示全部。 */
    Db[] only() default {};

    /** 不在这些库上运行。 */
    Db[] except() default {};
}
