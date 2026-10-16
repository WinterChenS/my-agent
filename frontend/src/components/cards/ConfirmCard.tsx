import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { ConfirmCardData } from "@/lib/agui/types";

/**
 * 确认卡：Agent 通过 CUSTOM `confirm_card` 主动要求用户确认，选项回传后继续 run。
 *
 * <p>data 结构见技术方案第 6 节冻结契约。
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
  return (
    <Card className="border-sky-400/70" data-testid="confirm-card">
      <CardHeader className="p-3">
        <CardTitle className="text-sm">{data?.title ?? "请确认"}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 p-3 pt-0">
        {data?.message && <p className="text-sm whitespace-pre-wrap">{data.message}</p>}
        <div className="flex flex-wrap gap-2">
          {options.map((option) => (
            <Button
              key={option.value}
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => onChoose?.(option.value)}
            >
              {option.label}
            </Button>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
