/**
 * `/` 选 Skill 的纯逻辑（Phase 10 / D21）。
 *
 * <p>抽成纯函数是为了能单测：选中后要做去重、要能删除、要按关键字过滤，
 * 这些规则跟 UI 无关。
 *
 * <p>2026-10 调整（用户反馈）：技能不再是「挂在输入框上方的一排块」，而是**消息里的行内块**——
 * 它出现在用户当时输入 `/` 的位置，随消息一起发出去、一起显示在气泡里。
 * 所以这里加了 parts ⇄ text 的转换：正文里用 `[[skill:名字]]` 标记，气泡据此还原成块。
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

/** 消息的一段：普通文本，或一个技能块。 */
export type MentionPart =
  | { type: "text"; text: string }
  | { type: "skill"; id: number; name: string };

/** 技能在正文里的标记：`[[skill:名字]]`。 */
const SKILL_MARKER = /\[\[skill:([^\]]+)\]\]/g;

/** parts → 纯文本（技能变成标记）。发给后端的就是这个，气泡也据此还原。 */
export function partsToText(parts: MentionPart[]): string {
  return parts
    .map((part) => (part.type === "skill" ? `[[skill:${part.name}]]` : part.text))
    .join("");
}

/**
 * 纯文本 → parts（把标记还原成技能块）。
 *
 * <p>用于：气泡渲染、刷新后回放历史消息。
 * 名字里的 `]]` 会被截断，所以构造标记时也不允许出现 `]]`（技能名来自平台，实际不会）。
 */
export function textToParts(text: string | null | undefined): MentionPart[] {
  if (!text) {
    return [];
  }
  const parts: MentionPart[] = [];
  let lastIndex = 0;
  SKILL_MARKER.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = SKILL_MARKER.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push({ type: "text", text: text.slice(lastIndex, match.index) });
    }
    // id 在文本里没有携带（历史消息可能只剩名字），用 -1 占位：仅用于展示与删除
    parts.push({ type: "skill", id: -1, name: match[1] });
    lastIndex = match.index + match[0].length;
  }
  if (lastIndex < text.length) {
    parts.push({ type: "text", text: text.slice(lastIndex) });
  }
  return parts;
}

/** 从 parts 里取技能 id（发送时通过 forwardedProps.skillIds 交给后端，让技能真正生效）。 */
export function partsSkillIds(parts: MentionPart[]): number[] {
  return parts.filter((p) => p.type === "skill" && p.id >= 0).map((p) => (p as { id: number }).id);
}

/** parts 里是否没有可发送的内容（只有空白文字、或什么都没有）。有技能块就不算空。 */
export function partsAreEmpty(parts: MentionPart[]): boolean {
  return !parts.some((p) => p.type === "skill" || p.text.trim() !== "");
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
