import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import type { ConfirmCardData } from "@/lib/agui/types";

/**
 * 选择卡：Agent 通过 CUSTOM `confirm_card` 把**候选答案**摆成按钮，用户点选后该文字作为
 * 下一条用户消息回到模型。
 *
 * 为什么不是固定「继续 / 取消」：用户实测抱怨「每次弹卡都只有这两个」——
 * 想改语气、换方案时根本没得选，只能再打字说一遍。现在候选由模型给
 * （`demo_confirm_card` 的 `options`），`allowCustom` 时还能自己写别的。
 *
 * **卡片一出现就能点**：以前按钮在"运行中"被禁用，用户点了没反应，
 * 体感像"冻结很久，要等 Agent 说完"。现在忙的时候点了会由父组件自动排队，
 * 本组件只负责把选择交出去。
 *
 * data 结构见技术方案第 6 节冻结契约（`ConfirmCardData`）。
 */
export function ConfirmCard({
  data,
  busy,
  onChoose,
}: {
  data: ConfirmCardData;
  busy?: boolean;
  onChoose?: (value: string) => void;
}) {
  const options = Array.isArray(data?.options) ? data.options : [];
  const [custom, setCustom] = useState("");

  const submitCustom = () => {
    const text = custom.trim();
    if (!text || busy) {
      return;
    }
    onChoose?.(text);
    setCustom("");
  };

  return (
    <Card className="border-sky-400/70" data-testid="confirm-card">
      <CardHeader className="p-3">
        <CardTitle className="text-sm">{data?.title ?? "请选择"}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 p-3 pt-0">
        {data?.message && <p className="text-sm whitespace-pre-wrap">{data.message}</p>}
        <div className="flex flex-wrap gap-2">
          {options.map((option) => (
            <Button
              key={option.value}
              size="sm"
              variant="outline"
              onClick={() => onChoose?.(option.value)}
            >
              {option.label}
            </Button>
          ))}
        </div>
        {busy ? (
          <p className="text-muted-foreground text-xs">
            Agent 还在输出：现在点选也会立即记下，这一轮结束后自动发出。
          </p>
        ) : null}
        {data?.allowCustom ? (
          <div className="flex items-center gap-2">
            <Input
              value={custom}
              placeholder="或者自己写一个答案…"
              data-testid="confirm-card-custom"
              onChange={(event) => setCustom(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  submitCustom();
                }
              }}
            />
            <Button size="sm" disabled={!custom.trim()} onClick={submitCustom}>
              就用这个
            </Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
