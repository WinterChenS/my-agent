import { describe, expect, it } from "vitest";
import {
  addSkill,
  filterSkills,
  removeSkill,
  skillsQuery,
  stripQuery,
  type SkillOption,
} from "./selection";

const skills: SkillOption[] = [
  { id: 1, name: "报告生成", description: "生成周报", status: "ENABLED" },
  { id: 2, name: "code-review", description: "Review code", status: "ENABLED" },
  { id: 3, name: "停用的技能", description: "x", status: "DISABLED" },
];

describe("skillsQuery（`/` 触发）", () => {
  it("输入框以 / 开头时给出关键字", () => {
    expect(skillsQuery("/")).toBe("");
    expect(skillsQuery("/报")).toBe("报");
    expect(skillsQuery("帮我看看 /code")).toBe("code");
  });

  it("没有 / 时不弹菜单", () => {
    expect(skillsQuery("普通消息")).toBeNull();
    expect(skillsQuery("")).toBeNull();
    // 已经是完整词（后面跟了空格）就不再弹
    expect(skillsQuery("/报告 继续")).toBeNull();
  });
});

describe("filterSkills", () => {
  it("关键字匹配名称或描述，且排除禁用技能", () => {
    expect(filterSkills(skills, "报告", []).map((s) => s.id)).toEqual([1]);
    expect(filterSkills(skills, "review", []).map((s) => s.id)).toEqual([2]);
    expect(filterSkills(skills, "", []).map((s) => s.id)).toEqual([1, 2]);
  });

  it("已选中的不再出现在候选里", () => {
    const selected = [{ id: 1, name: "报告生成" }];
    expect(filterSkills(skills, "", selected).map((s) => s.id)).toEqual([2]);
  });
});

describe("addSkill / removeSkill", () => {
  it("按 id 去重", () => {
    const first = addSkill([], skills[0]);
    expect(first).toEqual([{ id: 1, name: "报告生成" }]);
    expect(addSkill(first, skills[0])).toHaveLength(1);
  });

  it("可以整块删除", () => {
    const selected = [
      { id: 1, name: "报告生成" },
      { id: 2, name: "code-review" },
    ];
    expect(removeSkill(selected, 1)).toEqual([{ id: 2, name: "code-review" }]);
    expect(removeSkill(selected, 99)).toHaveLength(2);
  });
});

describe("stripQuery", () => {
  it("选中技能后清掉输入框里的 /关键字，保留其它文字", () => {
    expect(stripQuery("/报")).toBe("");
    expect(stripQuery("帮我看看 /co")).toBe("帮我看看");
    expect(stripQuery("没有斜杠")).toBe("没有斜杠");
  });
});
