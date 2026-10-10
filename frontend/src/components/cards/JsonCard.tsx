import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

/**
 * 未知 CUSTOM 事件降级卡：以 JSON 折叠展示，保证界面不崩（Task 7.2 门禁）。
 */
export function JsonCard({ name, data }: { name: string; data: unknown }) {
  let text: string;
  try {
    text = typeof data === "string" ? data : JSON.stringify(data, null, 2);
  } catch {
    text = String(data);
  }

  return (
    <Card className="border-amber-300/60">
      <CardHeader className="p-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <Badge variant="outline">未识别事件</Badge>
          <span className="font-mono text-xs">{name || "(anonymous)"}</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="p-3 pt-0">
        <pre className="bg-muted max-h-64 overflow-auto rounded-md p-2 text-xs">{text}</pre>
      </CardContent>
    </Card>
  );
}
