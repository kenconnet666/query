package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.expr.typed.ExprBigint;
import org.dpdns.zerodep.query.expr.typed.ExprBinary;
import org.dpdns.zerodep.query.expr.typed.ExprBoolean;
import org.dpdns.zerodep.query.expr.typed.ExprDate;
import org.dpdns.zerodep.query.expr.typed.ExprDecimal;
import org.dpdns.zerodep.query.expr.typed.ExprDouble;
import org.dpdns.zerodep.query.expr.typed.ExprInteger;
import org.dpdns.zerodep.query.expr.typed.ExprSmallint;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.expr.typed.ExprTime;
import org.dpdns.zerodep.query.expr.typed.ExprTimestamp;
import org.dpdns.zerodep.query.expr.typed.ExprTimestampTz;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.function.UnaryOperator;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按逻辑 SQL 类型分的表达式接口：结果仍是本类型的方法都重写了，写错地方的条件和赋值立即报错。 */
@DisplayName("类型接口与条件块")
class TypedExprTest {
    private static final TestDialect DIALECT = new TestDialect();

    /** 每个类型接口都要按本类型重写这些方法，漏了的话 coalesce、max 之后就点不出本类型的方法（参考库 Querydsl、Jimmer 都出过这种错）。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {ExprText.class, ExprSmallint.class, ExprInteger.class, ExprBigint.class, ExprDecimal.class, ExprDouble.class,
        ExprBoolean.class, ExprDate.class, ExprTimestamp.class, ExprTime.class, ExprTimestampTz.class, ExprBinary.class})
    void 结果仍是本类型的方法都按本类型重写(Class<?> type) throws NoSuchMethodException {
        for (Method m : new Method[] {
            type.getDeclaredMethod("coalesce", Object.class),
            type.getDeclaredMethod("coalesce", Expr.class),
            type.getDeclaredMethod("coalesce", Expr.class, Expr.class),
            type.getDeclaredMethod("nullif", Object.class),
            type.getDeclaredMethod("max"),
            type.getDeclaredMethod("min"),
            type.getDeclaredMethod("filter", Runnable.class),
            type.getDeclaredMethod("over", UnaryOperator.class),
            type.getDeclaredMethod("retype", Expr.class),
        }) {
            assertEquals(type, m.getReturnType(), type.getSimpleName() + "." + m.getName());
        }
    }

    @Test
    void 计算结果按宽的类型提升且值不变的函数保住本类型() {
        assertInstanceOf(ExprDecimal.class, lit(1).plus(lit(new BigDecimal("2"))));
        assertInstanceOf(ExprBigint.class, lit(1).plus(2L));
        assertInstanceOf(ExprDouble.class, lit(new BigDecimal("1")).times(2.0));
        String sql = from(Lips.class)
            .select(l -> cols(
                l.unit().coalesce("EA").upper().as("unit"),
                l.quantity().sum().plus(l.quantity().max()).as("total"),
                caseWhen(() -> l.unit().eq("KG"), l.quantity()).otherwise(BigDecimal.ZERO).times(2).as("weighted"),
                lag(l.quantity(), 1).over(w -> w.orderBy(l.delivery())).minus(l.quantity()).as("change")))
            .toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT UPPER(COALESCE(LIPS.VRKME, 'EA')) AS \"unit\", (SUM(LIPS.LFIMG) + MAX(LIPS.LFIMG)) AS \"total\""), sql);
        assertTrue(sql.contains("(CASE WHEN LIPS.VRKME = 'KG' THEN LIPS.LFIMG ELSE 0 END * 2) AS \"weighted\""), sql);
        assertTrue(sql.contains("(LAG(LIPS.LFIMG, 1) OVER (ORDER BY LIPS.VBELN ASC) - LIPS.LFIMG) AS \"change\""), sql);
    }

    @Test
    void 连续的加法和乘法并成一个括号() {
        String sql = from(Event.class).select(e -> e.id().plus(1).plus(2).times(3).as("x")).toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT ((EVENT.ID + 1 + 2) * 3) AS \"x\""), sql);
    }

    @Test
    void 条件写在条件块外或投影里立即报错() {
        var outside = assertThrows(IllegalStateException.class, () -> lit("x").eq("y"));
        assertTrue(outside.getMessage().contains("outside a condition block"), outside.getMessage());
        var inSelect = assertThrows(IllegalStateException.class, () -> from(Lips.class).where(l -> {
            l.unit().eq("KG");
            l.delivery().in(from(Lips.class).select(x -> {
                x.unit().eq("EA");
                return x.delivery();
            }));
        }));
        assertTrue(inSelect.getMessage().contains("bool(() -> ...)"), inSelect.getMessage());
    }

    @Test
    void 赋值写在SET块外报错且列以外不能赋值() {
        var e = assertThrows(IllegalStateException.class, () -> from(Lips.class).where(l -> l.unit().to("KG")));
        assertTrue(e.getMessage().contains("set(t -> { ... })"), e.getMessage());
    }

    @Test
    void 只有聚合能接filter和over() {
        assertThrows(IllegalArgumentException.class, () -> from(Lips.class).select(l -> l.quantity().filter(() -> l.unit().eq("KG"))));
        assertThrows(IllegalArgumentException.class, () -> from(Lips.class).select(l -> l.quantity().over(w -> w)));
    }

    @Test
    void 区间在OR块里是一个选项() {
        // range 和缺一端的 between 写两个比较，要合成一个条件：在 any 的块里分开写就成了 x >= a OR x < b
        String sql = from(Event.class).skipEmpty().where(e -> any(() -> {
            e.id().range(1, 5);
            e.id().between(10, null);
            e.id().eq(20);
        })).select(e -> e.id()).toSql(DIALECT).sql();
        assertTrue(sql.endsWith("WHERE ((EVENT.ID >= ? AND EVENT.ID < ?) OR (EVENT.ID >= ?) OR (EVENT.ID = ?))"), sql);
    }
}
