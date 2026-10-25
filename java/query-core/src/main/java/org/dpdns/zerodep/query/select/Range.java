package org.dpdns.zerodep.query.select;

/**
 * 条件对象里区间字段的值（{@code @Where(op = RANGE)}），两端都含，空的一端不加条件。JSON 写成 {@code {"start": …, "end": …}}，
 * 与 vue-tsx-pro 的 DateRangePicker 给出的值一样；地址栏里写两个同名参数时字段改用 {@code List<T>}。
 *
 * @param start 起端，null 表示不限
 * @param end   止端，null 表示不限
 */
public record Range<T>(T start, T end) { }
