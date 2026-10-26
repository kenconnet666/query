package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.wire.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("业务错误码")
class ErrorCodesTest {
    /** spec/error-codes.json 里 package 为 core 的码，按清单的先后。 */
    @SuppressWarnings("unchecked")
    private static List<String> coreCodes() throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(p.resolve("spec/error-codes.json"))) p = p.getParent();
        var spec = (Map<String, Object>) Json.read(Files.readString(p.resolve("spec/error-codes.json")));
        return ((List<Map<String, Object>>) spec.get("codes")).stream()
            .filter(c -> "core".equals(c.get("package"))).map(c -> (String) c.get("code")).toList();
    }

    @Test
    void 与共用清单一致() throws Exception {
        assertEquals(coreCodes(), Arrays.stream(QueryErrorCode.values()).map(QueryErrorCode::code).toList());
    }

    @Test
    void 表格请求读不懂时带request_invalid() {
        var bad = List.of(Map.of("page", List.of("x")), Map.of("pageSize", List.of("0")), Map.of("condition", List.of("{\"field\":\"a\"")),
            Map.of("condition", List.of("{\"field\":\"a\",\"op\":\"like\",\"value\":1}")));
        for (var params : bad) {
            var e = assertThrows(InvalidRequestException.class, () -> TableRequest.parse(params), params::toString);
            assertEquals("query.request-invalid", e.code().code());
        }
        Database db = TestDatabase.dryRun(c -> { });
        var q = from(Lips.class).select(l -> cols(l.delivery(), l.quantity()));
        for (var params : List.of(Map.of("sort", List.of("nope")), Map.of("filter.nope", List.of("x")), Map.of("search.quantity", List.of("1")))) {
            var e = assertThrows(InvalidRequestException.class, () -> db.table(q, TableRequest.parse(params)), params::toString);
            assertEquals(QueryErrorCode.REQUEST_INVALID, e.code());
        }
    }
}
