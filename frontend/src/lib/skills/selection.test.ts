import { describe, expect, it } from "vitest";
import {
  partsAreEmpty,
  partsSkillIds,
  partsToText,
  textToParts,
  filterSkills,
  skillsQuery,
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

describe("parts ⇄ text（技能是行内块）", () => {
  it("技能序列化成标记，位置保留在中间", () => {
    const parts = [
      { type: "text" as const, text: "请用 " },
      { type: "skill" as const, id: 12, name: "订单查询" },
      { type: "text" as const, text: " 帮我看看" },
    ];
    expect(partsToText(parts)).toBe("请用 [[skill:订单查询]] 帮我看看");
  });

  it("标记还原成技能块时位置不变（气泡按这个渲染）", () => {
    const parts = textToParts("请用 [[skill:订单查询]] 帮我看看");
    expect(parts).toEqual([
      { type: "text", text: "请用 " },
      { type: "skill", id: -1, name: "订单查询" },
      { type: "text", text: " 帮我看看" },
    ]);
  });

  it("没有标记时就是一段纯文本", () => {
    expect(textToParts("普通一句话")).toEqual([{ type: "text", text: "普通一句话" }]);
    expect(textToParts(null)).toEqual([]);
  });

  it("标记在开头/结尾也能正确切分", () => {
    expect(textToParts("[[skill:a]]x")).toEqual([
      { type: "skill", id: -1, name: "a" },
      { type: "text", text: "x" },
    ]);
    expect(textToParts("x[[skill:a]]")).toEqual([
      { type: "text", text: "x" },
      { type: "skill", id: -1, name: "a" },
    ]);
  });

  it("取技能 id（发送时交给后端让技能生效），历史消息的 -1 不算", () => {
    expect(
      partsSkillIds([
        { type: "skill", id: 12, name: "a" },
        { type: "text", text: "x" },
        { type: "skill", id: -1, name: "b" },
        { type: "skill", id: 34, name: "c" },
      ]),
    ).toEqual([12, 34]);
  });

  it("只有技能块没有文字也算「有内容」，纯空白算「空」", () => {
    expect(partsAreEmpty([{ type: "skill", id: 1, name: "a" }])).toBe(false);
    expect(partsAreEmpty([{ type: "text", text: "   " }])).toBe(true);
    expect(partsAreEmpty([])).toBe(true);
  });
});
