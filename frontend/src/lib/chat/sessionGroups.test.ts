import { describe, expect, it } from "vitest";
import { daysAgo, groupOf, groupSessionsByTime, sessionTime } from "./sessionGroups";

const NOW = new Date("2026-10-11T15:00:00+08:00");

function at(iso: string, id = 1) {
  return { id, title: `会话 ${id}`, lastActiveAt: iso };
}

describe("会话时间分组", () => {
  it("按今天/昨天/最近7天/最近30天/更早分类", () => {
    expect(groupOf(at("2026-10-11T09:00:00+08:00"), NOW)).toBe("今天");
    expect(groupOf(at("2026-10-10T23:30:00+08:00"), NOW)).toBe("昨天");
    expect(groupOf(at("2026-10-06T10:00:00+08:00"), NOW)).toBe("最近 7 天");
    expect(groupOf(at("2026-09-20T10:00:00+08:00"), NOW)).toBe("最近 30 天");
    expect(groupOf(at("2026-08-01T10:00:00+08:00"), NOW)).toBe("更早");
  });

  it("边界：昨天 00:00 与今天 00:00 都不算跨天错位", () => {
    expect(daysAgo(at("2026-10-11T00:00:00+08:00"), NOW)).toBe(0);
    expect(daysAgo(at("2026-10-10T00:00:00+08:00"), NOW)).toBe(1);
    expect(daysAgo(at("2026-10-09T23:59:59+08:00"), NOW)).toBe(2);
  });

  it("未来时间（时钟漂移）并入今天，不产生空组", () => {
    expect(groupOf(at("2026-10-12T10:00:00+08:00"), NOW)).toBe("今天");
  });

  it("取不到时间时归入更早（不丢条目）", () => {
    expect(sessionTime({ id: 1, lastActiveAt: null, createdAt: undefined })).toBeNull();
    expect(groupOf({ id: 1, lastActiveAt: "不是时间" }, NOW)).toBe("更早");
  });

  it("只返回非空分组，且组内保持传入顺序", () => {
    const groups = groupSessionsByTime(
      [
        at("2026-10-11T09:00:00+08:00", 1),
        at("2026-10-10T09:00:00+08:00", 2),
        at("2026-10-11T08:00:00+08:00", 3),
        at("2026-08-01T09:00:00+08:00", 4),
      ],
      NOW,
    );
    expect(groups.map((g) => g.label)).toEqual(["今天", "昨天", "更早"]);
    expect(groups[0].items.map((s) => s.id)).toEqual([1, 3]);
    expect(groups[1].items.map((s) => s.id)).toEqual([2]);
    expect(groups[2].items.map((s) => s.id)).toEqual([4]);
  });

  it("空列表返回空分组", () => {
    expect(groupSessionsByTime([], NOW)).toEqual([]);
  });

  it("优先用 lastActiveAt，缺失时用 createdAt", () => {
    const session = { id: 9, lastActiveAt: null, createdAt: "2026-10-11T01:00:00+08:00" };
    expect(sessionTime(session)?.toISOString()).toBe(
      new Date("2026-10-11T01:00:00+08:00").toISOString(),
    );
    expect(groupOf(session, NOW)).toBe("今天");
  });
});
