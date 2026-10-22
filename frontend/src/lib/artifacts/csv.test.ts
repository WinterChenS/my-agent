import { describe, expect, it } from "vitest";
import { CSV_MAX_COLS, CSV_MAX_ROWS, delimiterLabel, parseCsv } from "./csv";

describe("parseCsv", () => {
  it("基础：第一行当表头，逗号分列", () => {
    const table = parseCsv("名称,数量\n苹果,3\n香蕉,5\n");
    expect(table.header).toEqual(["名称", "数量"]);
    expect(table.rows).toEqual([
      ["苹果", "3"],
      ["香蕉", "5"],
    ]);
    expect(table.delimiter).toBe(",");
    expect(table.truncated).toBe(false);
  });

  it("引号里的逗号 / 换行 / 双引号都算内容", () => {
    const table = parseCsv('名称,备注\n"上海, 浦东","第一行\n第二行"\n"他说""你好""",普通\n');
    expect(table.header).toEqual(["名称", "备注"]);
    expect(table.rows).toEqual([
      ["上海, 浦东", "第一行\n第二行"],
      ['他说"你好"', "普通"],
    ]);
  });

  it("自动认分隔符：分号 / 制表符", () => {
    expect(parseCsv("a;b;c\n1;2;3\n").delimiter).toBe(";");
    expect(parseCsv("a\tb\tc\n1\t2\t3\n").delimiter).toBe("\t");
    // 没有任何分隔符 → 按逗号（单列表）
    expect(parseCsv("只有一列\n值\n").delimiter).toBe(",");
  });

  it("CRLF 与单独 CR 都当换行；尾部空行不占一行", () => {
    const table = parseCsv("a,b\r\n1,2\r\n\r\n3,4\r\n");
    expect(table.header).toEqual(["a", "b"]);
    expect(table.rows).toEqual([
      ["1", "2"],
      ["3", "4"],
    ]);
  });

  it("剥掉 Excel 的 UTF-8 BOM（否则第一列表头带看不见的字符）", () => {
    const table = parseCsv("\uFEFF名称,数量\n苹果,3\n");
    expect(table.header[0]).toBe("名称");
  });

  it("行尾多余的逗号不会画出空白列", () => {
    const table = parseCsv("a,b,\n1,2,\n");
    expect(table.header).toEqual(["a", "b"]);
    expect(table.rows).toEqual([["1", "2"]]);
  });

  it("列数不齐时补空字符串，不抛错；多出来的列保留（不丢数据）", () => {
    const table = parseCsv("a,b,c\n1\n1,2,3,4\n");
    // 列数取所有行里最宽的那行（4）—— 多出来的那列有值，不能为了表头好看就丢掉
    expect(table.header).toEqual(["a", "b", "c", ""]);
    expect(table.rows).toEqual([
      ["1", "", "", ""],
      ["1", "2", "3", "4"],
    ]);
  });

  it("引号没闭合不抛异常（预览宁可少画也不要红屏）", () => {
    const table = parseCsv('a,b\n"没闭合,2\n');
    expect(table.header).toEqual(["a", "b"]);
    expect(table.rows[0]?.[0]).toContain("没闭合");
  });

  it("超出行/列上限时截断并置 truncated", () => {
    const wide = Array.from({ length: CSV_MAX_COLS + 5 }, (_, i) => `c${i}`).join(",");
    const wideTable = parseCsv(`${wide}\n1,2,3\n`);
    expect(wideTable.header).toHaveLength(CSV_MAX_COLS);
    expect(wideTable.truncated).toBe(true);

    const manyRows = ["a,b", ...Array.from({ length: CSV_MAX_ROWS + 10 }, (_, i) => `${i},x`)].join("\n");
    const tallTable = parseCsv(manyRows);
    expect(tallTable.rows).toHaveLength(CSV_MAX_ROWS);
    expect(tallTable.truncated).toBe(true);
  });

  it("delimiterLabel 给中文名（界面据此说明按什么分列）", () => {
    expect(delimiterLabel(",")).toBe("逗号");
    expect(delimiterLabel(";")).toBe("分号");
    expect(delimiterLabel("\t")).toBe("制表符");
  });
});
