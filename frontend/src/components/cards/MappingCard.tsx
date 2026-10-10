import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { MappingCardData } from "@/lib/agui/types";

/**
 * 映射卡：结构化展示键值/表格数据（CUSTOM `mapping_card`）。
 *
 * <p>data 结构见技术方案第 6 节冻结契约。
 */
export function MappingCard({ data }: { data: MappingCardData }) {
  const columns = Array.isArray(data?.columns) ? data.columns : [];
  const rows = Array.isArray(data?.rows) ? data.rows : [];

  return (
    <Card>
      <CardHeader className="p-3">
        <CardTitle className="text-sm">{data?.title ?? "映射结果"}</CardTitle>
      </CardHeader>
      <CardContent className="p-3 pt-0">
        {columns.length === 0 ? (
          <p className="text-muted-foreground text-xs">（无列定义）</p>
        ) : (
          <div className="overflow-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  {columns.map((column) => (
                    <TableHead key={column.key}>{column.label}</TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row, index) => (
                  <TableRow key={index}>
                    {columns.map((column) => (
                      <TableCell key={column.key} className="text-xs">
                        {formatCell(row?.[column.key])}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}
