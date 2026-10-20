/**
 * 从 `.docx` 里抽出**纯文本**（**零依赖**：docx 本质就是个 zip，正文在 `word/document.xml`）。
 *
 * <p>为什么自己解压而不是引 `mammoth`：用户明确要求不引 Word 渲染库（决策 1C）。代价是
 * **没有版式、没有表格结构、没有图片** —— 只保证「能读到字」。所以界面上要如实说明，
 * 并且在解析失败时引导下载（见 ArtifactPreviewBody 的 docx 分支）。
 *
 * <p>解析步骤（只用浏览器原生能力）：
 * <ol>
 *   <li>从尾部找 EOCD（zip 中央目录结束记录），拿到中央目录的位置与条目数；</li>
 *   <li>在中央目录里按名字找 `word/document.xml`；</li>
 *   <li>method 8 → `DecompressionStream("deflate-raw")` 解；method 0 → 直接用（**必须支持 stored**，
 *       浏览器/测试都能造出这种最省事的样本）；</li>
 *   <li>`DOMParser` 解析 XML，按 `<w:p>` 分段，取 `<w:t>` 文本，`<w:tab/>`/`<w:br/>` 转制表/换行。</li>
 * </ol>
 *
 * <p>不支持：zip64（超大文件）、加密、`word/document.xml` 之外的正文（页眉页脚、脚注）。
 */

/** 中央目录结束记录（EOCD）签名 */
const SIG_EOCD = 0x06054b50;
/** 中央目录条目签名 */
const SIG_CENTRAL = 0x02014b50;
/** 本地文件头签名 */
const SIG_LOCAL = 0x04034b50;
/** 正文条目：Word 的 document.xml 固定在这里 */
const DOCUMENT_ENTRY = "word/document.xml";
/** WordprocessingML 命名空间 */
const W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

/** 解析失败（调用方据此显示「无法解析，请下载」）。 */
export class DocxParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DocxParseError";
  }
}

export interface ZipEntry {
  name: string;
  /** 0 = stored（不压缩），8 = deflate */
  method: number;
  /** 压缩后的字节数 */
  compressedSize: number;
  /** 解压后的字节数 */
  size: number;
  /** 本地文件头在文件里的偏移 */
  localOffset: number;
}

function findEocd(view: DataView): number {
  // EOCD 固定 22 字节，后面可能跟最多 65535 字节的注释 → 从尾部往前扫这个窗口
  const min = Math.max(0, view.byteLength - 22 - 0xffff);
  for (let offset = view.byteLength - 22; offset >= min; offset -= 1) {
    if (view.getUint32(offset, true) === SIG_EOCD) {
      return offset;
    }
  }
  return -1;
}

/** 读出 zip 的中央目录（`listZipEntries` 只读目录，不碰文件内容）。 */
export function listZipEntries(bytes: Uint8Array): ZipEntry[] {
  if (bytes.byteLength < 22) {
    throw new DocxParseError("文件太小，不是有效的 zip");
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const eocd = findEocd(view);
  if (eocd < 0) {
    throw new DocxParseError("找不到 zip 中央目录（可能不是 zip 文件，或是 .doc 旧格式）");
  }
  const count = view.getUint16(eocd + 10, true);
  let offset = view.getUint32(eocd + 16, true);
  if (offset === 0xffffffff) {
    throw new DocxParseError("不支持 zip64");
  }

  const entries: ZipEntry[] = [];
  for (let i = 0; i < count; i += 1) {
    if (offset + 46 > view.byteLength || view.getUint32(offset, true) !== SIG_CENTRAL) {
      throw new DocxParseError("zip 中央目录损坏");
    }
    const nameLength = view.getUint16(offset + 28, true);
    const extraLength = view.getUint16(offset + 30, true);
    const commentLength = view.getUint16(offset + 32, true);
    const nameBytes = bytes.subarray(offset + 46, offset + 46 + nameLength);
    entries.push({
      name: new TextDecoder("utf-8").decode(nameBytes),
      method: view.getUint16(offset + 10, true),
      compressedSize: view.getUint32(offset + 20, true),
      size: view.getUint32(offset + 24, true),
      localOffset: view.getUint32(offset + 42, true),
    });
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

/** 取某个条目的**原始（未解压）**字节。 */
function readEntryBytes(bytes: Uint8Array, entry: ZipEntry): Uint8Array {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const local = entry.localOffset;
  if (local + 30 > view.byteLength || view.getUint32(local, true) !== SIG_LOCAL) {
    throw new DocxParseError("zip 本地文件头损坏");
  }
  // 本地头的「名字/扩展字段长度」**可能与中央目录不同**（word 会往里塞 data descriptor），必须读本地头
  const nameLength = view.getUint16(local + 26, true);
  const extraLength = view.getUint16(local + 28, true);
  const start = local + 30 + nameLength + extraLength;
  const end = start + entry.compressedSize;
  if (end > bytes.byteLength) {
    throw new DocxParseError("zip 条目越界（文件被截断？）");
  }
  return bytes.subarray(start, end);
}

/** 按条目的压缩方式还原成明文（method 0 = stored，8 = deflate）。 */
async function decompress(raw: Uint8Array, method: number): Promise<Uint8Array> {
  if (method === 0) {
    return raw;
  }
  if (method !== 8) {
    throw new DocxParseError(`不支持的压缩方式 ${method}`);
  }
  if (typeof DecompressionStream === "undefined") {
    throw new DocxParseError("当前环境不支持 DecompressionStream");
  }
  /*
   * `deflate-raw` 对应 zip 里的裸 deflate 流（没有 zlib 头）。
   *
   * 不用 `new Blob([raw]).stream()`：jsdom 的 Blob 没有 `stream()`（单测会直接炸），
   * 而且这里也不需要它 —— 直接把字节喂给解压流即可。
   * 读和写必须**同时**进行：先写完再读，大文件会把内部缓冲区写满而互相等待。
   */
  const decompressor = new DecompressionStream("deflate-raw");
  const reading = readAllChunks(decompressor.readable);
  const writer = decompressor.writable.getWriter();
  await writer.write(raw);
  await writer.close();
  return await reading;
}

/** 把一个可读流读完（只在 docx 解压这一处用，所以不做通用封装）。 */
async function readAllChunks(stream: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    if (value) {
      chunks.push(value);
      total += value.length;
    }
  }
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.length;
  }
  return merged;
}

/** 一个 `<w:p>` 里的可见文本（`<w:t>` 拼接，tab/br 转换；不递归进嵌套段落，避免重复）。 */
function paragraphText(paragraph: Element, out: { text: string }) {
  for (const child of Array.from(paragraph.children)) {
    const isWord = child.namespaceURI === W_NS;
    const name = child.localName;
    if (isWord && name === "t") {
      out.text += child.textContent ?? "";
    } else if (isWord && name === "tab") {
      out.text += "\t";
    } else if (isWord && (name === "br" || name === "cr")) {
      out.text += "\n";
    } else if (isWord && (name === "p" || name === "tbl")) {
      // 嵌套段落/表格由外层 `getElementsByTagNameNS("p")` 单独遍历，这里跳过避免文本重复
      continue;
    } else {
      paragraphText(child, out);
    }
  }
}

/** 把 `document.xml` 的 XML 文本抽成纯文本（按段落一行）。 */
export function documentXmlToText(xml: string): string {
  const doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length > 0) {
    throw new DocxParseError("document.xml 不是合法 XML");
  }
  const paragraphs = Array.from(doc.getElementsByTagNameNS(W_NS, "p"));
  const lines = paragraphs.map((paragraph) => {
    const out = { text: "" };
    paragraphText(paragraph, out);
    return out.text;
  });
  // 全空段落（Word 常留）压成空行；末尾多余空行去掉
  const text = lines.join("\n").replace(/\r\n?/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  return text;
}

/**
 * 抽 `.docx` 的纯文本。
 *
 * @throws {DocxParseError} 不是 zip / 没有 `word/document.xml` / 压缩方式不支持 / XML 坏了
 */
export async function extractDocxText(bytes: Uint8Array): Promise<string> {
  const entry = listZipEntries(bytes).find((item) => item.name === DOCUMENT_ENTRY);
  if (!entry) {
    throw new DocxParseError("没有 word/document.xml（可能不是 .docx）");
  }
  const xmlBytes = await decompress(readEntryBytes(bytes, entry), entry.method);
  return documentXmlToText(new TextDecoder("utf-8").decode(xmlBytes));
}
