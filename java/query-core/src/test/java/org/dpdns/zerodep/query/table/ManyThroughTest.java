package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Access.User;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("多对多与子集合上的条件对象")
class ManyThroughTest {
    private static final TestDialect DIALECT = new TestDialect();

    public record UserRoles(String name, List<String> roles) { }

    @Test
    void any查询中间表_条件里的目标列在子查询里LEFT_JOIN过去() {
        String sql = from(User.class).where(u -> u.roles().any(r -> r.name().eq("admin"))).select(u -> u.name()).toSql(DIALECT).sql();
        assertTrue(sql.endsWith("""
            WHERE EXISTS (SELECT 1
            FROM QUERY_IT.ACC_USER_ROLE USERROLE
            LEFT JOIN QUERY_IT.ACC_ROLE ROLE ON ROLE.ID = USERROLE.ROLE_ID
            WHERE USERROLE.USER_ID = "USER".ID AND ROLE.NAME = ?)"""), sql);
    }

    @Test
    void 行数是中间表的行数_用不到目标列时不连接目标表() {
        String sql = from(User.class).select(u -> cols(u.name(), u.roles().count().as("roles"))).toSql(DIALECT).sql();
        assertTrue(sql.contains("(SELECT COUNT(*)\nFROM QUERY_IT.ACC_USER_ROLE USERROLE\nWHERE USERROLE.USER_ID = \"USER\".ID)"), sql);
        assertFalse(sql.contains("ACC_ROLE"), sql);
    }

    @Test
    void list用中间表的键批量取子行_投影和排序拿到的是角色() {
        Query<UserRoles> q = from(User.class).select(u -> row(UserRoles::new, u.name(), u.roles().orderBy(r -> r.sortNo()).list(r -> r.name())));
        List<Rendered> children = new ArrayList<>();
        List<UserRoles> rows = q.mapRows(List.of(new Object[] {"ann", 1}, new Object[] {"bob", 2}, new Object[] {"cy", 3}), child -> {
            children.add(child.toSql(DIALECT));
            return List.<Object[]>of(new Object[] {"admin", 1}, new Object[] {"editor", 1}, new Object[] {"editor", 2});
        });
        assertEquals(List.of(new UserRoles("ann", List.of("admin", "editor")), new UserRoles("bob", List.of("editor")), new UserRoles("cy", List.of())), rows);
        assertEquals("""
            SELECT ROLE.NAME AS "name", USERROLE.USER_ID AS "userId"
            FROM QUERY_IT.ACC_USER_ROLE USERROLE
            LEFT JOIN QUERY_IT.ACC_ROLE ROLE ON ROLE.ID = USERROLE.ROLE_ID
            WHERE USERROLE.USER_ID IN (?, ?, ?)
            ORDER BY ROLE.SORT_NO ASC""", children.get(0).sql());
    }

    @Test
    void first在中间表上排名_再沿导航取目标行的列() {
        String sql = from(User.class).select(u -> cols(u.name(), u.roles().first(r -> r.sortNo().asc()).name().as("top"))).toSql(DIALECT).sql();
        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY USERROLE.USER_ID ORDER BY ROLE2.SORT_NO ASC)"), sql);
        assertTrue(sql.endsWith("LEFT JOIN QUERY_IT.ACC_ROLE ROLE ON ROLE.ID = USERROLE_FIRST.\"roleId\""), sql);
    }

    @Test
    void 条件对象的路径经过多对多时写成EXISTS() {
        record UserSearch(@Where("roles.name") String role) { }
        String sql = from(User.class).where(new UserSearch("admin")).select(u -> u.name()).toSql(DIALECT).sql();
        assertTrue(sql.contains("WHERE EXISTS (SELECT 1\nFROM QUERY_IT.ACC_USER_ROLE USERROLE\nLEFT JOIN QUERY_IT.ACC_ROLE ROLE"), sql);
    }

    @Test
    void through写在关系之后_where与orderBy写在它后面() {
        var e = assertThrows(IllegalStateException.class,
            () -> from(User.class).select(u -> u.userRoles().where(ur -> ur.roleId().eq(1)).through(ur -> ur.role()).count()));
        assertTrue(e.getMessage().contains("through(...) goes right after"), e.getMessage());
    }

    @Test
    void Many的where也收条件对象_前端的条件能筛子集合() {
        record LineFilter(String unit, String delivery) { }
        String sql = from(Mara.class).select(m -> cols(m.material(), m.deliveries().where(new LineFilter("KG", " ")).count().as("kg"))).toSql(DIALECT).sql();
        assertTrue(sql.contains("WHERE LIPS.MATNR = MARA.MATNR AND LIPS.MANDT = MARA.MANDT AND LIPS.VRKME = ?)"), sql);
        record Bad(String plant) { }
        assertThrows(IllegalArgumentException.class, () -> from(Mara.class).select(m -> m.deliveries().where(new Bad("1000")).count()));
    }
}
