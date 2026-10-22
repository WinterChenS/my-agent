/**
 * CSV / TSV 解析（**零依赖**，够看就行）。
 *
 * <p>为什么自己写而不是引 `papaparse`：预览只需要「把表格画出来」，不写回、不做类型推断。
 * 按 RFC 4180 处理这几种真实会遇到的情况就够了：
 * <ul>
 *   <li>字段用双引号包起来时，里面的分隔符/换行/逗号都算**内容**（`"上海, 浦东"`、单元格内换行）；</li>
 *   <li>引号里的双引号用两个表示（`""` → `"`）；</li>
 *   <li>行尾 `\r\n` 与单独的 `\r` 都认（Windows/老 Mac 导出的文件）；</li>
 *   <li>UTF-8 BOM（Excel「另存为 CSV」默认带）先剥掉，否则第一列表头会多一个不可见字符。</li>
 * </ul>
 *
 * <p>分隔符不写死逗号：中文环境常见 Excel 导出用 `;`（区域设置里逗号是小数点），
 * 所以先在第一行（引号外）数一下 `,` `;` `\t` 谁多就用谁，都没有就按逗号。
 *
 * <p>**不做**的事：类型转换（一律字符串）、编码猜测（假定调用方给的是 UTF-8 文本）、
 * 公式求值。解析失败（引号没闭合）不抛异常 —— 预览宁可显示成一行，也不要一个红屏。
 */

/** 预览里最多画多少行 / 列（十万行的文件全塞进 DOM 会卡死） */
export const CSV_MAX_ROWS = 200;
export const CSV_MAX_COLS = 40;

export type CsvDelimiter = "," | ";" | "\t";

export interface CsvTable {
  /** 第一行**当作表头**；只有一行数据时它也是表头（界面自己决定要不要强调） */
  header: string[];
  /** 表头之后的数据行 */
  rows: string[][];
  delimiter: CsvDelimiter;
  /** 因为超过 {@link CSV_MAX_ROWS}/{@link CSV_MAX_COLS} 而被截掉了内容 */
  truncated: boolean;
}

/** 第一行里、引号外的分隔符计数，挑最多的那个。 */
function sniffDelimiter(text: string): CsvDelimiter {
  const counts: Record<CsvDelimiter, number> = { ",": 0, ";": 0, "\t": 0 };
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === '"') {
      // 引号内的 "" 是转义，仍然在引号内
      quoted = !quoted;
      continue;
    }
    if (!quoted && (ch === "\n" || ch === "\r")) {
      break; // 只看第一行
    }
    if (!quoted && (ch === "," || ch === ";" || ch === "\t")) {
      counts[ch] += 1;
    }
  }
  const best = (Object.keys(counts) as CsvDelimiter[]).reduce((a, b) =>
    counts[a] >= counts[b] ? a : b,
  );
  return counts[best] > 0 ? best : ",";
}

/**
 * 把 CSV/TSV 文本解析成二维数组（第一行单独给出来当表头）。
 *
 * <p>行/列超限时**截断并置 `truncated`**，不报错 —— 调用方据此提示「只显示前 N 行」。
 */
export function parseCsv(raw: string): CsvTable {
  // Excel 的 UTF-8 BOM：不剥掉的话第一列表头会带一个看不见的 \uFEFF
  const text = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
  const delimiter = sniffDelimiter(text);

  const all: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  let truncated = false;

  const pushField = () => {
    row.push(field);
    field = "";
  };
  const pushRow = () => {
    pushField();
    all.push(row);
    row = [];
  };

  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];

    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i += 1; // 跳过成对的第二个引号
        } else {
          quoted = false;
        }
      } else {
        field += ch;
      }
      continue;
    }

    if (ch === '"') {
      quoted = true;
      continue;
    }
    if (ch === delimiter) {
      pushField();
      continue;
    }
    if (ch === "\r" || ch === "\n") {
      // \r\n 当成一个换行
      if (ch === "\r" && text[i + 1] === "\n") {
        i += 1;
      }
      // 末尾的空行不占一行（但中间的空行保留，便于对照原文件的行号）
      if (row.length > 0 || field.length > 0) {
        pushRow();
      }
      continue;
    }
    field += ch;
  }
  if (row.length > 0 || field.length > 0) {
    pushRow();
  }

  // 去掉每行尾部的空字段（`a,b,` 这种行尾逗号在导出文件里很常见，画出来是空白列很碍眼）
  const trimmed = all.map((cells) => {
    const copy = cells.slice();
    while (copy.length > 0 && copy[copy.length - 1].trim() === "") {
      copy.pop();
    }
    return copy;
  });

  const width = trimmed.reduce((max, cells) => Math.max(max, cells.length), 0);
  const columns = Math.min(width, CSV_MAX_COLS);
  if (width > columns) {
    truncated = true;
  }

  const normalize = (cells: string[]) => {
    const out: string[] = [];
    for (let c = 0; c < columns; c += 1) {
      out.push(cells[c] ?? "");
    }
    return out;
  };

  const head = trimmed[0] ?? [];
  const body = trimmed.slice(1);
  if (body.length > CSV_MAX_ROWS) {
    truncated = true;
  }

  return {
    header: normalize(head),
    rows: body.slice(0, CSV_MAX_ROWS).map(normalize),
    delimiter,
    truncated,
  };
}

/** 分隔符的中文名，用于界面提示（让用户知道为什么画成了 N 列）。 */
export function delimiterLabel(delimiter: CsvDelimiter): string {
  if (delimiter === "\t") {
    return "制表符";
  }
  if (delimiter === ";") {
    return "分号";
  }
  return "逗号";
}
