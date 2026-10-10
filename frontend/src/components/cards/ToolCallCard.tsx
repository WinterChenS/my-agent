import { useState } from "react";
import { ChevronDown, ChevronRight, Wrench } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import type { ChatItem } from "@/lib/agui/types";

type ToolItem = Extract<ChatItem, { kind: "tool" }>;

const STATE_LABEL: Record<ToolItem["state"], string> = {
  args: "参数接收中",
  ready: "待执行",
  done: "已完成",
};

/** 工具调用卡：折叠展示工具名 + 参数 + 结果（技术方案第 6 节）。 */
export function ToolCallCard({ item }: { item: ToolItem }) {
  const [open, setOpen] = useState(false);

  return (
    <Card className="border-dashed">
      <CardHeader className="p-3">
        <Button
          variant="ghost"
          size="sm"
          className="h-auto w-full justify-start gap-2 p-0"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
        >
          {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
          <Wrench className="size-4" />
          <span className="font-mono text-xs">{item.name}</span>
          <Badge variant={item.state === "done" ? "secondary" : "outline"} className="ml-auto">
            {STATE_LABEL[item.state]}
          </Badge>
        </Button>
      </CardHeader>
      {open && (
        <CardContent className="space-y-2 p-3 pt-0">
          <div>
            <div className="text-muted-foreground mb-1 text-xs">参数</div>
            <pre className="bg-muted max-h-64 overflow-auto rounded-md p-2 text-xs">
              {item.args || "（无）"}
            </pre>
          </div>
          {item.result !== null && (
            <div>
              <div className="text-muted-foreground mb-1 text-xs">结果</div>
              <pre className={cn("bg-muted max-h-64 overflow-auto rounded-md p-2 text-xs")}>
                {item.result}
              </pre>
            </div>
          )}
        </CardContent>
      )}
    </Card>
  );
}
