package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.testing.Access.Role;
import org.dpdns.zerodep.query.testing.Access.User;
import org.dpdns.zerodep.query.testing.Access.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.util.List;

import static org.dpdns.zerodep.query.Sql.bool;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.insertInto;
import static org.dpdns.zerodep.query.Sql.row;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 多对多（{@code through}）：经过中间表的 any、count、list、first 和条件对象。每个库建一次表，写入（与 TypeScript 的 many-to-many.live.test.ts 相同）：
 *
 * <pre>
 * ACC_USER 用户     ACC_ROLE 角色          ACC_USER_ROLE
 *   1  ann            1  admin   次序 1       1 → 1、1 → 2
 *   2  bob            2  editor  次序 2       2 → 2
 *   3  cy                                     （cy 没有角色）
 * </pre>
 */
@LiveTest
@DisplayName("多对多")
class ManyToManyTest {
    @Parameter Live live;

    record RoleRow(Integer id, String name, Integer sortNo) { }

    record UserRow(Integer id, String name) { }

    record UserRoleRow(Integer userId, Integer roleId) { }

    record Counted(String name, Long roles, Boolean hasRoles) { }

    record WithRoles(String name, List<String> roles, String top) { }

    /** 按角色名找用户：路径经过多对多。 */
    record UserSearch(@Where("roles.name") String role) { }

    @BeforeParameterizedClassInvocation
    static void tables(Live live) {
        live.recreate(Role.class, UserRole.class, User.class);
        var db = live.database(null);
        db.execute(insertInto(Role.class).values(new RoleRow(1, "admin", 1), new RoleRow(2, "editor", 2)));
        db.execute(insertInto(User.class).values(new UserRow(1, "ann"), new UserRow(2, "bob"), new UserRow(3, "cy")));
        db.execute(insertInto(UserRole.class).values(new UserRoleRow(1, 1), new UserRoleRow(1, 2), new UserRoleRow(2, 2)));
    }

    @Test
    void any与count查询中间表_条件里的角色列在子查询里连接过去() {
        assertEquals(List.of("ann"), live.db().list(from(User.class).where(u -> u.roles().any(r -> r.name().eq("admin"))).select(u -> u.name())));
        List<Counted> counts = live.db().list(from(User.class).orderBy(u -> u.id())
            .select(u -> cols(u.name(), u.roles().count().as("roles"), bool(() -> u.roles().any()).as("hasRoles"))).into(Counted.class));
        assertEquals(List.of(new Counted("ann", 2L, true), new Counted("bob", 1L, true), new Counted("cy", 0L, false)), counts);
    }

    @Test
    void list按中间表的键批量取角色_first取次序最小的角色() {
        List<WithRoles> rows = live.db().list(from(User.class).orderBy(u -> u.id()).select(u -> row(WithRoles::new, u.name(),
            u.roles().orderBy(r -> r.sortNo()).list(r -> r.name()), u.roles().first(r -> r.sortNo().asc()).name())));
        assertEquals(List.of(new WithRoles("ann", List.of("admin", "editor"), "admin"), new WithRoles("bob", List.of("editor"), "editor"),
            new WithRoles("cy", List.of(), null)), rows);
    }

    @Test
    void 条件对象的路径经过多对多() {
        assertEquals(List.of("ann", "bob"),
            live.db().list(from(User.class).where(new UserSearch("editor")).orderBy(u -> u.id()).select(u -> u.name())));
    }
}
