import { describe, expect, it } from "vitest";
import { deflateRawSync } from "node:zlib";
import { DocxParseError, documentXmlToText, extractDocxText, listZipEntries } from "./docxText";

/**
 * 自造一个最小 zip（**不引 fixture 二进制**，也不依赖任何 zip 库）。
 *
 * <p>两种压缩方式都要能造：stored（0，最省事）与 deflate（8）。CRC 留 0 —— 解析只关心
 * 名字/偏移/字节，不校验 CRC（真实 docx 也没问题）。
 */
function makeZip(entries: { name: string; data: Uint8Array; deflate?: boolean }[]): Uint8Array {
  const encoder = new TextEncoder();
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = encoder.encode(entry.name);
    const stored = entry.deflate ? new Uint8Array(deflateRawSync(entry.data)) : entry.data;
    const method = entry.deflate ? 8 : 0;

    const local = new Uint8Array(30 + nameBytes.length + stored.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(8, method, true);
    lv.setUint32(18, stored.length, true);
    lv.setUint32(22, entry.data.length, true);
    lv.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);
    local.set(stored, 30 + nameBytes.length);
    locals.push(local);

    const central = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(10, method, true);
    cv.setUint32(20, stored.length, true);
    cv.setUint32(24, entry.data.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint32(42, offset, true);
    central.set(nameBytes, 46);
    centrals.push(central);

    offset += local.length;
  }

  const centralSize = centrals.reduce((sum, item) => sum + item.length, 0);
  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);

  const total = offset + centralSize + eocd.length;
  const zip = new Uint8Array(total);
  let cursor = 0;
  for (const chunk of [...locals, ...centrals, eocd]) {
    zip.set(chunk, cursor);
    cursor += chunk.length;
  }
  return zip;
}

const W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

/** 最小 document.xml：两段正文 + 制表符 + 换行 + 一个表格单元格段落。 */
const DOCUMENT_XML = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="${W_NS}">
  <w:body>
    <w:p><w:r><w:t>第一段</w:t></w:r><w:r><w:t xml:space="preserve"> 后半句</w:t></w:r></w:p>
    <w:p><w:r><w:t>甲</w:t><w:tab/><w:t>乙</w:t><w:br/><w:t>丙</w:t></w:r></w:p>
    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>单元格文字</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
  </w:body>
</w:document>`;

const docx = (deflate?: boolean) =>
  makeZip([
    { name: "[Content_Types].xml", data: new TextEncoder().encode("<Types/>") },
    { name: "_rels/.rels", data: new TextEncoder().encode("<Relationships/>") },
    { name: "word/document.xml", data: new TextEncoder().encode(DOCUMENT_XML), deflate },
  ]);

describe("extractDocxText", () => {
  it("stored 条目：按段落取文字，制表/换行转换，表格里的段落也能读到", async () => {
    const text = await extractDocxText(docx(false));
    expect(text).toBe("第一段 后半句\n甲\t乙\n丙\n单元格文字");
  });

  it("deflate 条目：与 stored 结果一致（走 DecompressionStream 的 deflate-raw）", async () => {
    // jsdom 里没有 DecompressionStream；没有就跳过这条（浏览器里由 e2e 覆盖真实解压）
    if (typeof DecompressionStream === "undefined") {
      return;
    }
    expect(await extractDocxText(docx(true))).toBe(await extractDocxText(docx(false)));
  });

  it("没有 word/document.xml → 明确报错（调用方显示「无法解析，请下载」）", async () => {
    const zip = makeZip([{ name: "word/styles.xml", data: new TextEncoder().encode("<x/>") }]);
    await expect(extractDocxText(zip)).rejects.toBeInstanceOf(DocxParseError);
  });

  it("根本不是 zip（老 .doc / 随便一个文件）→ 明确报错", async () => {
    const notZip = new Uint8Array(64).fill(0x41);
    await expect(extractDocxText(notZip)).rejects.toBeInstanceOf(DocxParseError);
  });

  it("列中央目录：条目名与偏移可用（解析器自己也要能被单测）", () => {
    const entries = listZipEntries(docx(false));
    expect(entries.map((entry) => entry.name)).toEqual([
      "[Content_Types].xml",
      "_rels/.rels",
      "word/document.xml",
    ]);
    expect(entries[2].localOffset).toBeGreaterThan(0);
    expect(entries[2].method).toBe(0);
  });
});

describe("documentXmlToText", () => {
  it("压缩多余空行与行尾空白，但保留段内空格", () => {
    const xml = `<w:document xmlns:w="${W_NS}"><w:body>
      <w:p><w:r><w:t>a</w:t></w:r></w:p>
      <w:p></w:p><w:p></w:p>
      <w:p><w:r><w:t>b c</w:t></w:r></w:p>
    </w:body></w:document>`;
    expect(documentXmlToText(xml)).toBe("a\n\nb c");
  });

  it("不是合法 XML → 报错而不是静默返回空", () => {
    expect(() => documentXmlToText("<w:document>")).toThrow(DocxParseError);
  });
});
