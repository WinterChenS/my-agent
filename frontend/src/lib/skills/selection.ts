/**
 * `/` 选 Skill 的纯逻辑（Phase 10 / D21）。
 *
 * <p>抽成纯函数是为了能单测：选中后要做去重、要能删除、要按关键字过滤，
 * 这些规则跟 UI 无关。
 */

export interface SkillOption {
  id: number;
  name: string;
  description?: string | null;
  status?: string;
}

export interface SelectedSkill {
  id: number;
  name: string;
}

/** 输入框里以 `/` 开头时给出待选关键字；否则返回 null（不弹菜单）。 */
export function skillsQuery(value: string): string | null {
  const match = /(?:^|\s)\/([^\s/]*)$/.exec(value);
  return match ? match[1] : null;
}

/** 用关键字过滤可选项，已被选中的不再出现（避免重复添加）。 */
export function filterSkills(
  skills: SkillOption[],
  query: string,
  selected: SelectedSkill[],
): SkillOption[] {
  const chosen = new Set(selected.map((item) => item.id));
  const keyword = query.trim().toLowerCase();
  return skills
    .filter((skill) => skill.status !== "DISABLED")
    .filter((skill) => !chosen.has(skill.id))
    .filter((skill) => {
      if (!keyword) {
        return true;
      }
      const name = skill.name.toLowerCase();
      const desc = (skill.description ?? "").toLowerCase();
      return name.includes(keyword) || desc.includes(keyword);
    });
}

/** 添加一个技能：按 id 去重。 */
export function addSkill(selected: SelectedSkill[], skill: SkillOption): SelectedSkill[] {
  if (selected.some((item) => item.id === skill.id)) {
    return selected;
  }
  return [...selected, { id: skill.id, name: skill.name }];
}

export function removeSkill(selected: SelectedSkill[], id: number): SelectedSkill[] {
  return selected.filter((item) => item.id !== id);
}

/** 选中技能后把输入框里残留的 `/关键字` 去掉（保留其它文字）。 */
export function stripQuery(value: string): string {
  return value.replace(/(^|\s)\/[^\s/]*$/, "$1").replace(/\s+$/, "");
}
