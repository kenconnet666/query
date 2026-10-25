package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.table.column.ColInteger;
import org.dpdns.zerodep.query.table.column.ColText;

/**
 * 用户、角色和用户角色（中间表）：多对多（{@code through}）的对照场景，与 TypeScript 的 access.ts 一一对应。
 * query-it 的样例：用户 1 有角色 1（admin）、2（editor），用户 2 有角色 2，用户 3 没有角色。
 */
public final class Access {
    /** 测试表所在的 schema。 */
    public static final String SCHEMA = "QUERY_IT";

    /** 只放表，不能实例化。 */
    private Access() { }

    /** 角色；sortNo 小的排前面。 */
    @Table(schema = SCHEMA, value = "ACC_ROLE")
    public interface Role extends Entity {
        @Id @Column("ID") ColInteger<Integer> id();

        @Column("NAME") ColText<String> name();

        @Column("SORT_NO") ColInteger<Integer> sortNo();
    }

    /** 用户与角色的对应，一行一对。 */
    @Table(schema = SCHEMA, value = "ACC_USER_ROLE")
    public interface UserRole extends Entity {
        @Id @Column("USER_ID") ColInteger<Integer> userId();

        @Id @Column("ROLE_ID") ColInteger<Integer> roleId();

        /** 这一行的角色。 */
        default Role role() { return one(Role.class, r -> r.id().eq(roleId())); }
    }

    /** 用户。 */
    @Table(schema = SCHEMA, value = "ACC_USER")
    public interface User extends Entity {
        @Id @Column("ID") ColInteger<Integer> id();

        @Column("NAME") ColText<String> name();

        /** 到中间表的一对多。 */
        default Many<UserRole> userRoles() { return many(UserRole.class, ur -> ur.userId().eq(id())); }

        /** 多对多：经过中间表到角色。 */
        default Many<Role> roles() { return userRoles().through(UserRole::role); }
    }
}
