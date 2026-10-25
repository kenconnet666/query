import assert from "node:assert/strict";
import { it } from "node:test";
import { addDays, addHours, addMinutes, addMonths, addSeconds, addYears, date, day, daysBetween, from, hour, lit, minute, month, monthStart, second, year } from "@zerodep-dev/query-core";
import { Event } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * 日期与时间：读写不受进程时区影响，日期和时间的部分、加减与月初在各库上结果相同。
 * 数据是样例数据的 EVENTS 两行：1 号 2024-03-31 02:30（欧洲夏令时开始的凌晨），2 号 2011-12-30 00:00（萨摩亚跳过的一天）。
 * 与 Java 的 DateTimeTest 对应。
 */
liveSuite("日期与时间", (live) => {
  it("日期和时间戳不受进程时区影响", async () => {
    // 这两个时刻在某些时区里不存在：经过 Date 或会话时区换算的驱动会读成别的值。
    const original = process.env.TZ;
    try {
      for (const zone of ["Europe/Berlin", "Pacific/Apia", "Asia/Shanghai"]) {
        process.env.TZ = zone;
        const events = await live.db.list(from(Event).orderBy((e) => e.id).selectAs(Event));
        assert.equal(events[0]!.onDay, "2024-03-31", zone);
        assert.equal(events[0]!.atTime, "2024-03-31 02:30:00", zone);
        assert.equal(events[1]!.onDay, "2011-12-30", zone);
        assert.deepEqual(await live.db.list(from(Event).where((e) => e.onDay.eq("2011-12-30")).select((e) => e.id)), [2], zone);
        assert.deepEqual(await live.db.list(from(Event).where((e) => e.atTime.eq("2024-03-31 02:30:00")).select((e) => e.id)), [1], zone);
      }
    } finally {
      if (original === undefined) delete process.env.TZ;
      else process.env.TZ = original;
    }
  });

  it("日期部分与日期加减", async () => {
    // 半开区间 [2011-01-01, 2025-01-01) 里的两行；2024-03-31 减一个月是 2 月 29 日（闰年月末）。
    const rows = await live.db.list(
      from(Event)
        .where((e) => e.onDay.range("2011-01-01", "2025-01-01"))
        .orderBy((e) => e.id)
        .select((e) => ({
          id: e.id,
          y: year(e.onDay),
          m: month(e.atTime),
          d: day(e.onDay),
          plusDays: addDays(e.onDay, 3),
          minusMonth: addMonths(e.onDay, -1),
          plusYear: addYears(e.onDay, 1),
          days: daysBetween(e.onDay, lit("2024-04-30", date)),
        })),
    );
    const days = (Date.UTC(2024, 3, 30) - Date.UTC(2011, 11, 30)) / 86_400_000;
    assert.deepEqual(rows.map((r) => `${r.id}:${r.y}:${r.m}:${r.d}:${r.plusDays}:${r.minusMonth}:${r.plusYear}:${r.days}`), [
      "1:2024:3:31:2024-04-03:2024-02-29:2025-03-31:30",
      `2:2011:12:30:2012-01-02:2011-11-30:2012-12-30:${days}`,
    ]);
  });

  it("时间部分与加减小时分钟秒和月初", async () => {
    const rows = await live.db.list(
      from(Event)
        .orderBy((e) => e.id)
        .select((e) => ({
          id: e.id,
          h: hour(e.atTime),
          mi: minute(e.atTime),
          s: second(e.atTime),
          plusHours: addHours(e.atTime, 25),
          minusMinutes: addMinutes(e.atTime, -31),
          plusSeconds: addSeconds(e.atTime, 59),
          monthStart: monthStart(e.onDay),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.id}:${r.h}:${r.mi}:${r.s}:${r.plusHours}:${r.minusMinutes}:${r.plusSeconds}:${r.monthStart}`), [
      "1:2:30:0:2024-04-01 03:30:00:2024-03-31 01:59:00:2024-03-31 02:30:59:2024-03-01",
      "2:0:0:0:2011-12-31 01:00:00:2011-12-29 23:29:00:2011-12-30 00:00:59:2011-12-01",
    ]);
  });
});
