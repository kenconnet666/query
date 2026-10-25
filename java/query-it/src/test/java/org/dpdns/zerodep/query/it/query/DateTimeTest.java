package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.EventRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;

import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 日期与时间：读写不受 JVM 时区影响，日期和时间的部分、加减与月初在各库上结果相同。
 * 数据是 {@link SampleData} 的 EVENTS 两行：1 号 2024-03-31 02:30（欧洲夏令时开始的凌晨），2 号 2011-12-30 00:00（萨摩亚跳过的一天）。
 */
@LiveTest
@DisplayName("日期与时间")
class DateTimeTest {
    @Parameter Live live;

    @Test
    void 日期和时间戳不受JVM时区影响() {
        // 这两个时刻在某些时区里不存在：经过 java.util.Date 或会话时区换算的驱动会读成别的值。
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("Europe/Berlin", "Pacific/Apia", "Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                List<EventRow> events = live.db().list(from(Event.class).orderBy(e -> e.id()).selectAs(EventRow.class));
                assertEquals(LocalDate.of(2024, 3, 31), events.get(0).onDay(), zone);
                assertEquals(LocalDateTime.of(2024, 3, 31, 2, 30), events.get(0).atTime(), zone);
                assertEquals(LocalDate.of(2011, 12, 30), events.get(1).onDay(), zone);
                assertEquals(List.of(2), live.db().list(from(Event.class).where(e -> e.onDay().eq(LocalDate.of(2011, 12, 30))).select(e -> e.id())), zone);
                assertEquals(List.of(1), live.db().list(from(Event.class).where(e -> e.atTime().eq(LocalDateTime.of(2024, 3, 31, 2, 30))).select(e -> e.id())), zone);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void 日期部分与日期加减() {
        // 半开区间 [2011-01-01, 2025-01-01) 里的两行；2024-03-31 减一个月是 2 月 29 日（闰年月末）。
        List<String> rows = live.db().list(from(Event.class)
                .where(e -> e.onDay().range(LocalDate.of(2011, 1, 1), LocalDate.of(2025, 1, 1)))
                .orderBy(e -> e.id())
                .select(e -> cols(e.id(), e.onDay().year().as("y"), e.atTime().month().as("m"), e.onDay().day().as("d"),
                    e.onDay().addDays(3).as("plusDays"), e.onDay().addMonths(-1).as("minusMonth"), e.onDay().addYears(1).as("plusYear"),
                    e.onDay().daysBetween(lit(LocalDate.of(2024, 4, 30))).as("days"))))
            .stream().map(r -> r.get("id") + ":" + r.get("y") + ":" + r.get("m") + ":" + r.get("d") + ":" + r.get("plusDays") + ":"
                + r.get("minusMonth") + ":" + r.get("plusYear") + ":" + r.get("days"))
            .toList();
        long days = ChronoUnit.DAYS.between(LocalDate.of(2011, 12, 30), LocalDate.of(2024, 4, 30));
        assertEquals(List.of("1:2024:3:31:2024-04-03:2024-02-29:2025-03-31:30", "2:2011:12:30:2012-01-02:2011-11-30:2012-12-30:" + days), rows);
    }

    @Test
    void 时间部分与加减小时分钟秒和月初() {
        List<String> rows = live.db().list(from(Event.class)
                .orderBy(e -> e.id())
                .select(e -> cols(e.id(), e.atTime().hour().as("h"), e.atTime().minute().as("mi"), e.atTime().second().as("s"),
                    e.atTime().addHours(25).as("plusHours"), e.atTime().addMinutes(-31).as("minusMinutes"), e.atTime().addSeconds(59).as("plusSeconds"),
                    e.onDay().monthStart().as("monthStart"))))
            .stream().map(r -> r.get("id") + ":" + r.get("h") + ":" + r.get("mi") + ":" + r.get("s") + ":" + r.get("plusHours") + ":"
                + r.get("minusMinutes") + ":" + r.get("plusSeconds") + ":" + r.get("monthStart"))
            .toList();
        assertEquals(List.of("1:2:30:0:2024-04-01T03:30:2024-03-31T01:59:2024-03-31T02:30:59:2024-03-01",
            "2:0:0:0:2011-12-31T01:00:2011-12-29T23:29:2011-12-30T00:00:59:2011-12-01"), rows);
    }
}
