import { bigint, boolean, index, key, lowercase, many, one, store } from "@zerodep-dev/idb";

/** IndexedDB 本地存储的测试仓库：单元测试（fake-indexeddb）和浏览器实测共用。 */

/** 用户：便签的多对一目标。 */
@store("users")
export class User {
  @key id!: string;
  name!: string;
}

/** 便签：各种索引都有一个。 */
@store("notes", { indexes: [["owner", "createdAt"]] })
export class Note {
  @key id!: string;
  @index owner!: string;
  @index({ unique: true }) slug!: string;
  @index({ multiEntry: true }) tags!: string[];
  @index({ codec: boolean }) pinned!: boolean;
  @index({ codec: lowercase }) title!: string;
  @index({ optional: true }) dueOn!: string | null;
  createdAt!: Date;
  /** 多对一：owner 是用户的主键。 */
  @one(() => User, "owner") user?: User;
  /** 一对多：评论的 noteId 指回便签。 */
  @many(() => Comment, "noteId") comments?: Comment[];

  /** 标题的长度，用来确认读回的行是这个类的实例。 */
  titleLength(): number {
    return this.title.length;
  }
}

/** 评论：noteId 的索引供一对多装载。 */
@store("comments")
export class Comment {
  @key id!: string;
  @index noteId!: string;
  text!: string;
}

/** 主键是 bigint：经编码器存成保持顺序的文本。 */
@store("counters")
export class Counter {
  @key({ codec: bigint }) n!: bigint;
  label!: string;
}

/** 复合主键。 */
@store("cells")
export class Cell {
  @key row!: number;
  @key col!: number;
  value!: string;
}

/** 便签的一行，字段缺省可覆盖。 */
export function note(id: string, changes: Partial<Note> = {}): Note {
  return Object.assign(new Note(), {
    id,
    owner: "u1",
    slug: `slug-${id}`,
    tags: [],
    pinned: false,
    title: `Title ${id}`,
    dueOn: null,
    createdAt: new Date(Date.UTC(2026, 0, Number(id.replace(/\D/g, "")) || 1)),
    ...changes,
  });
}
