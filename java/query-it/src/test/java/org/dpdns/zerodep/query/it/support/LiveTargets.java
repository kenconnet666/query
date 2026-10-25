package org.dpdns.zerodep.query.it.support;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.ParameterDeclarations;
import org.junit.platform.commons.support.AnnotationSupport;

import java.lang.reflect.Field;
import java.util.stream.Stream;

/**
 * 给 {@link LiveTest} 标注的测试类提供要跑的库（每个库一份 {@link Live}），并在每个测试方法开始前准备好当前的库。
 * 没有连接变量的库在这一步就跳过：如果等测试里第一次用到 {@code live} 时才跳过，写在 {@code assertThrows} 里的调用会把跳过当成异常接住。
 */
public final class LiveTargets implements ArgumentsProvider, BeforeEachCallback {
    /** 按测试类上的注解和 QUERY_DATABASES 选库。 */
    @Override public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
        LiveTest test = AnnotationSupport.findAnnotation(context.getRequiredTestClass(), LiveTest.class)
            .orElseThrow(() -> new IllegalStateException(context.getRequiredTestClass().getName() + " is not annotated with @LiveTest"));
        return Live.selected(test).map(Arguments::of);
    }

    /** 准备测试实例上 {@code Live} 类型的字段（也就是 {@code @Parameter Live live}）。 */
    @Override public void beforeEach(ExtensionContext context) throws IllegalAccessException {
        Object test = context.getRequiredTestInstance();
        for (Field field : test.getClass().getDeclaredFields()) {
            if (field.getType() != Live.class) continue;
            field.trySetAccessible();
            ((Live) field.get(test)).ready();
        }
    }
}
