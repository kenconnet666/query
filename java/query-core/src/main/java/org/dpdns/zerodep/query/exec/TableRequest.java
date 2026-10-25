package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.select.ConditionTree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次表格查询的请求：页码、每页条数、排序、已知的总数和表头筛选，与 vue-tsx-pro 的 {@code tableParams} 写出的地址栏参数一一对应（0016）：
 * <pre>
 * page=2 &amp; pageSize=20 &amp; sort=-amount,name &amp; total=368 &amp; filter.status=draft &amp; filter.status=paid &amp; search.supplier=启航
 * &amp; condition={"any":[{"field":"amount","op":"gte","value":10000},{"field":"urgent","op":"eq","value":true}]}
 * </pre>
 * 其余参数是查询栏的条件，由 Spring 绑定到条件对象（{@code where(filter)}）。交给 {@link Database#table} 执行：
 * <pre>{@code
 * @GetMapping("/orders")
 * Object orders(@RequestParam MultiValueMap<String, String> params, OrderFilter filter) {
 *     return db.table(orders().where(filter), TableRequest.parse(params)).toWire();
 * }
 * }</pre>
 *
 * @param page     页码，从 1 开始
 * @param pageSize 每页条数
 * @param sort     排序串（{@link org.dpdns.zerodep.query.select.Query#sortBy}），null 是查询自己的顺序
 * @param total    前端已知的总数（只翻页、换排序时沿用上一次的），有它时不再执行 COUNT；null 表示要数
 * @param filter   表头的多选筛选：结果列名 → 选中的值（{@link org.dpdns.zerodep.query.select.Query#filterBy}）
 * @param search   表头的关键字筛选：结果列名 → 关键字（{@link org.dpdns.zerodep.query.select.Query#searchBy}）
 * @param condition 高级筛选的条件树（{@link org.dpdns.zerodep.query.select.Query#conditionBy}，0017）；没有时为 null
 */
public record TableRequest(int page, int pageSize, String sort, Long total, Map<String, List<String>> filter, Map<String, String> search,
                           ConditionTree condition) {
    /** 没有 pageSize 参数时的每页条数，同 vue-tsx-pro 的默认值。 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页条数的上限，同 Spring Data 的 {@code max-page-size}；超过时报错，而不是悄悄少给。 */
    public static final int MAX_PAGE_SIZE = 2000;

    /** 校验页码、条数和总数，复制并冻结两张筛选表。 */
    public TableRequest {
        if (page < 1) throw new IllegalArgumentException("page must be at least 1, got " + page);
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be at least 1, got " + pageSize);
        if (total != null && total < 0) throw new IllegalArgumentException("total must not be negative, got " + total);
        filter = filter == null ? Map.of() : Map.copyOf(filter);
        search = search == null ? Map.of() : Map.copyOf(search);
    }

    /** 读地址栏参数（Spring 的 {@code MultiValueMap} 就是这种形状），每页条数不超过 {@link #MAX_PAGE_SIZE}。 */
    public static TableRequest parse(Map<String, ? extends List<String>> params) { return parse(params, MAX_PAGE_SIZE); }

    /**
     * 读地址栏参数：{@code page}、{@code pageSize}、{@code sort}、{@code total} 取第一个值，{@code filter.列名} 取全部值，
     * {@code search.列名} 取第一个值，{@code condition} 是条件树的 JSON 文字；其余参数不管。
     * 写错的数字、超过 maxPageSize 的条数、写错的条件树报错（Web 层据此回 400）。
     */
    public static TableRequest parse(Map<String, ? extends List<String>> params, int maxPageSize) {
        Map<String, List<String>> filter = new LinkedHashMap<>();
        Map<String, String> search = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<String>> e : params.entrySet()) {
            List<String> values = e.getValue() == null ? List.of() : e.getValue();
            if (e.getKey().startsWith("filter.")) {
                List<String> kept = values.stream().filter(v -> v != null && !v.isBlank()).toList();
                if (!kept.isEmpty()) filter.put(e.getKey().substring("filter.".length()), kept);
            } else if (e.getKey().startsWith("search.") && !values.isEmpty() && values.get(0) != null && !values.get(0).isBlank()) {
                search.put(e.getKey().substring("search.".length()), values.get(0).trim());
            }
        }
        int pageSize = (int) number(params, "pageSize", DEFAULT_PAGE_SIZE);
        if (pageSize > maxPageSize) throw new IllegalArgumentException("pageSize must be at most " + maxPageSize + ", got " + pageSize);
        String sort = first(params, "sort");
        String total = first(params, "total");
        String condition = first(params, "condition");
        return new TableRequest((int) number(params, "page", 1), pageSize, sort == null || sort.isBlank() ? null : sort,
            total == null || total.isBlank() ? null : number(params, "total", 0), filter, search,
            condition == null || condition.isBlank() ? null : ConditionTree.parse(condition));
    }

    /** 参数的第一个值；没有时为 null。 */
    private static String first(Map<String, ? extends List<String>> params, String name) {
        List<String> values = params.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** 整数参数；没有或空白时是 fallback，写错时报错并指出参数名。 */
    private static long number(Map<String, ? extends List<String>> params, String name, long fallback) {
        String text = first(params, name);
        if (text == null || text.isBlank()) return fallback;
        try {
            long n = Long.parseLong(text.trim());
            if (!name.equals("total") && n > Integer.MAX_VALUE) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got '" + text + "'", e);
        }
    }
}
