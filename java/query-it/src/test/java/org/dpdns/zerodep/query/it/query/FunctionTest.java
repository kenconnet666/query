package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.caseOf;
import static org.dpdns.zerodep.query.Sql.caseWhen;
import static org.dpdns.zerodep.query.Sql.ceil;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.count;
import static org.dpdns.zerodep.query.Sql.cte;
import static org.dpdns.zerodep.query.Sql.divide;
import static org.dpdns.zerodep.query.Sql.floor;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.left;
import static org.dpdns.zerodep.query.Sql.lit;
import static org.dpdns.zerodep.query.Sql.locate;
import static org.dpdns.zerodep.query.Sql.lpad;
import static org.dpdns.zerodep.query.Sql.minus;
import static org.dpdns.zerodep.query.Sql.mod;
import static org.dpdns.zerodep.query.Sql.negate;
import static org.dpdns.zerodep.query.Sql.plus;
import static org.dpdns.zerodep.query.Sql.power;
import static org.dpdns.zerodep.query.Sql.replace;
import static org.dpdns.zerodep.query.Sql.right;
import static org.dpdns.zerodep.query.Sql.row;
import static org.dpdns.zerodep.query.Sql.rpad;
import static org.dpdns.zerodep.query.Sql.sign;
import static org.dpdns.zerodep.query.Sql.sqrt;
import static org.dpdns.zerodep.query.Sql.substring;
import static org.dpdns.zerodep.query.Sql.val;
import static org.dpdns.zerodep.query.Sql.values;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 文本与数值函数，以及 CASE 和 COALESCE：同一个写法在各库上结果相同。数据见 {@link SampleData}；
 * 大多数断言用 D1（物料 M1、数量 3、单位 EA、销售订单 S1）。
 */
@LiveTest
@DisplayName("文本与数值函数")
class FunctionTest {
    @Parameter Live live;

    @Test
    void 文本函数取负与多项求和以及按子串分组() {
        List<String> pieces = live.db().list(from(Lips.class)
            .where(l -> l.delivery().eq("D1"))
            .select(l -> row((a, b, c, d, e, negative, doubled) -> String.join("|", a, b, c, d, e, plain(negative), plain(doubled)),
                left(l.delivery(), 1),           // D
                substring(l.material(), 2),      // 1
                substring(l.unit(), 1, 1),       // E
                right(l.salesOrder(), 1),        // 1
                replace(l.unit(), "A", "-"),     // E-
                negate(l.quantity()),            // -3
                plus(List.of(l.quantity(), l.quantity())))));  // 6
        assertEquals(List.of("D|1|E|1|E-|-3|6"), pieces);
        List<String> prefixes = live.db().list(from(Lips.class)
            .groupBy(l -> substring(l.delivery(), 1, 1))
            .select(l -> row((prefix, lines) -> prefix + "=" + lines, substring(l.delivery(), 1, 1), count())));
        assertEquals(List.of("D=5"), prefixes);
    }

    /** 逗号分隔的代码，SAP 在一个字段里存多个 ATA 章节。 */
    record Code(String text) { }

    @Test
    void 按LOCATE找到的位置截取文本() {
        // 起止位置都是表达式：逗号之前是 head，之后是 tail；没有逗号时 LOCATE 是 0，head 取整个文本。
        Cte<Code> codes = cte("codes", values(Code.class, List.of(new Code("21,32"), new Code("05"))));
        List<String> rows = live.db().list(from(codes)
                .orderBy(c -> c.col(Code::text))
                .select(c -> {
                    Expr<String> text = c.col(Code::text);
                    Expr<Integer> comma = locate(text, ",");
                    return cols(text.as("text"), comma.as("comma"),
                        caseWhen(comma.gt(0), substring(text, lit(1), minus(comma, lit(1)))).otherwise(text).as("head"),
                        caseWhen(comma.gt(0), substring(text, plus(comma, lit(1)))).end().as("tail"));
                }))
            .stream().map(r -> r.get("text") + ":" + r.get("comma") + ":" + r.get("head") + ":" + r.get("tail")).toList();
        assertEquals(List.of("05:0:05:null", "21,32:3:21:32"), rows);
    }

    @Test
    void 简单CASE与多参数COALESCE() {
        // 制造商为空串时取零件号，再为空取 none；M4 的制造商是空串，所以取零件号 P1。
        List<String> rows = live.db().list(from(Mara.class)
                .orderBy(m -> m.material())
                .select(m -> cols(m.material(),
                    caseOf(m.materialType()).when("ZMAT", "material").otherwise("other").as("kind"),
                    m.manufacturer().nullif("").coalesce(m.partNumber().nullif(""), val("none")).as("maker"))))
            .stream().map(r -> r.get("material") + ":" + r.get("kind") + ":" + r.get("maker")).toList();
        assertEquals(List.of("M1:material:MK1", "M2:material:MK2", "M3:other:MK1", "M4:other:P1"), rows);
    }

    @Test
    void 数量折半后向下与向上取整() {
        List<String> rows = live.db().list(from(Lips.class)
                .orderBy(l -> l.delivery())
                .select(l -> {
                    Expr<BigDecimal> half = divide(l.quantity(), lit(new BigDecimal("2")));
                    return cols(l.delivery(), floor(half).as("down"), ceil(half).as("up"));
                }))
            .stream().map(r -> r.get("delivery") + ":" + plain(r.get("down")) + ":" + plain(r.get("up"))).toList();
        assertEquals(List.of("D1:1:2", "D2:1:1", "D3:2:3", "D4:3:4", "D5:0:1"), rows);
    }

    @Test
    void 补齐余数乘方开方与符号() {
        Map<String, Object> r = live.db().one(from(Lips.class)
            .where(l -> l.delivery().eq("D1"))
            .select(l -> cols(lpad(l.material(), 5, "0").as("padded"), rpad(l.unit(), 4, "*").as("unit"), mod(l.quantity(), 2).as("remainder"),
                power(l.quantity(), 2).as("squared"), sqrt(l.quantity()).as("root"), sign(minus(l.quantity(), lit(new BigDecimal("4")))).as("sign"))));
        assertEquals("000M1", r.get("padded"));
        assertEquals("EA**", r.get("unit"));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) r.get("remainder")), () -> "remainder " + r.get("remainder"));
        assertEquals(9.0, (Double) r.get("squared"), 1e-9);
        assertEquals(Math.sqrt(3), (Double) r.get("root"), 1e-9);
        assertEquals(-1, r.get("sign"), "3 - 4 的符号");
    }

    /** 小数去掉末尾的零。 */
    private static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }
}
