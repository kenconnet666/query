import { JoinDelete, JoinUpdate, type Ref, type Table } from "@zerodep-dev/query-core";

/** 联表更新：SET 和 WHERE 可以引用 joined 表，写成 UPDATE ... FROM；见 {@link JoinUpdate}。 */
export function updateJoin<T, S>(table: Table<T>, joined: Table<S>, on: (t: Ref<T>, s: Ref<S>) => void): JoinUpdate<T, S> {
  return JoinUpdate.of(table, joined, on);
}

/** 联表删除：WHERE 可以引用 joined 表，写成 DELETE ... USING；见 {@link JoinDelete}。 */
export function deleteJoin<T, S>(table: Table<T>, joined: Table<S>, on: (t: Ref<T>, s: Ref<S>) => void): JoinDelete<T, S> {
  return JoinDelete.of(table, joined, on);
}
