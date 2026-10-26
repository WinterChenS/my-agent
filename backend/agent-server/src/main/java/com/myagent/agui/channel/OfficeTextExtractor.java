package com.myagent.agui.channel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 把 Office 文件（xlsx / docx / pptx）转成纯文本，给模型读。
 *
 * <p>为什么需要：`read_file` 读的是文本；直接喂 xlsx 给模型只会得到一堆乱码或 ZIP 头。
 * 实测（用户场景）：钉钉里发来一个 Excel，文件确实存进了工作区，但模型"看不到内容"。
 *
 * <p>做法是**零新依赖**：Office 文件本质是 ZIP + XML，这里用 JDK 的 zip 与 DOM 解析取文本。
 * 不追求版面还原，只求"表格/段落/幻灯片里的文字能被读懂"，输出有长度上限。
 *
 * <p>安全：禁用 DOCTYPE 与外部实体（XXE），并限制单文件解析体积。
 */
public final class OfficeTextExtractor {

    /** 单次输出的最大字符数（防止一个巨型表格把上下文撑爆）。 */
    static final int MAX_OUTPUT_CHARS = 60_000;

    /** 允许解析的最大文件体积（超过就不转，避免内存与耗时风险）。 */
    static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;

    private OfficeTextExtractor() {}

    /** 抽取结果。 */
    public record Extracted(String kind, String text) {}

    /** 支持的扩展名（小写，不含点）。 */
    public static final List<String> SUPPORTED = List.of("xlsx", "docx", "pptx");

    public static boolean supports(String fileName) {
        return SUPPORTED.contains(extension(fileName));
    }

    /**
     * 抽取文本；不支持的类型或解析失败返回 {@link Optional#empty()}。
     *
     * <p>失败是**可接受的**：调用方会退化成"给路径让模型自己想办法"。
     */
    public static Optional<Extracted> extract(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_INPUT_BYTES) {
            return Optional.empty();
        }
        String ext = extension(fileName);
        try {
            return switch (ext) {
                case "xlsx" -> extractXlsx(bytes);
                case "docx" -> extractDocx(bytes);
                case "pptx" -> extractPptx(bytes);
                default -> Optional.empty();
            };
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    // ===================== xlsx =====================

    private static Optional<Extracted> extractXlsx(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = readZip(bytes, name ->
                name.startsWith("xl/") && (name.endsWith(".xml") || name.endsWith(".rels")));
        byte[] sharedStringsXml = entries.get("xl/sharedStrings.xml");
        List<String> shared = sharedStringsXml == null
                ? List.of()
                : readSharedStrings(sharedStringsXml);

        // sheet 名 → 内容；按 sheets 目录自然序（sheet1, sheet2 …）
        Map<String, byte[]> sheets = new LinkedHashMap<>();
        entries.keySet().stream()
                .filter(name -> name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
                .sorted()
                .forEach(name -> sheets.put(name, entries.get(name)));
        if (sheets.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, byte[]> sheet : sheets.entrySet()) {
            if (out.length() >= MAX_OUTPUT_CHARS) {
                break;
            }
            out.append("## ").append(sheet.getKey()).append('\n');
            appendSheet(out, sheet.getValue(), shared);
            out.append('\n');
        }
        String text = out.toString().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(new Extracted("excel", truncate(text)));
    }

    private static List<String> readSharedStrings(byte[] xml) throws Exception {
        Document doc = parse(xml);
        NodeList items = doc.getElementsByTagName("si");
        List<String> result = new ArrayList<>(items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            result.add(textOf((Element) items.item(i), "t"));
        }
        return result;
    }

    private static void appendSheet(StringBuilder out, byte[] xml, List<String> shared) throws Exception {
        Document doc = parse(xml);
        NodeList rows = doc.getElementsByTagName("row");
        for (int r = 0; r < rows.getLength(); r++) {
            if (out.length() >= MAX_OUTPUT_CHARS) {
                out.append("…（超出上限，已截断）\n");
                return;
            }
            Element row = (Element) rows.item(r);
            NodeList cells = row.getElementsByTagName("c");
            List<String> values = new ArrayList<>(cells.getLength());
            for (int c = 0; c < cells.getLength(); c++) {
                Element cell = (Element) cells.item(c);
                String type = cell.getAttribute("t");
                String value;
                if ("s".equals(type)) {
                    String idx = childText(cell, "v");
                    value = idx.isEmpty() ? "" : lookup(shared, idx);
                } else if ("inlineStr".equals(type)) {
                    value = textOf(cell, "t");
                } else {
                    value = childText(cell, "v");
                }
                values.add(value.replace('\n', ' ').strip());
            }
            // 去掉整行为空的输出，保持可读
            if (values.stream().anyMatch(v -> !v.isEmpty())) {
                out.append(String.join("\t", values)).append('\n');
            }
        }
    }

    private static String lookup(List<String> shared, String index) {
        try {
            int i = Integer.parseInt(index.trim());
            return i >= 0 && i < shared.size() ? shared.get(i) : "";
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    // ===================== docx =====================

    private static Optional<Extracted> extractDocx(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = readZip(bytes, name -> name.equals("word/document.xml"));
        byte[] document = entries.get("word/document.xml");
        if (document == null) {
            return Optional.empty();
        }
        Document doc = parse(document);
        NodeList paragraphs = doc.getElementsByTagName("w:p");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength() && out.length() < MAX_OUTPUT_CHARS; i++) {
            // docx 的文本节点是带命名空间前缀的 w:t（xlsx 里才是裸 t）
            Element paragraph = (Element) paragraphs.item(i);
            String line = textOf(paragraph, "w:t");
            if (line.isEmpty()) {
                line = textOf(paragraph, "t");
            }
            if (!line.isBlank()) {
                out.append(line).append('\n');
            }
        }
        String text = out.toString().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(new Extracted("word", truncate(text)));
    }

    // ===================== pptx =====================

    private static Optional<Extracted> extractPptx(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = readZip(bytes, name ->
                name.startsWith("ppt/slides/slide") && name.endsWith(".xml"));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        List<String> names = new ArrayList<>(entries.keySet());
        names.sort((a, b) -> Integer.compare(slideNo(a), slideNo(b)));
        int index = 1;
        for (String name : names) {
            if (out.length() >= MAX_OUTPUT_CHARS) {
                break;
            }
            out.append("## 第 ").append(index++).append(" 页\n");
            Document doc = parse(entries.get(name));
            NodeList texts = doc.getElementsByTagName("a:t");
            for (int i = 0; i < texts.getLength(); i++) {
                String text = texts.item(i).getTextContent();
                if (text != null && !text.isBlank()) {
                    out.append(text.strip()).append('\n');
                }
            }
            out.append('\n');
        }
        String text = out.toString().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(new Extracted("slides", truncate(text)));
    }

    private static int slideNo(String name) {
        String digits = name.replaceAll("\\D+", " ");
        for (String part : digits.trim().split("\\s+")) {
            try {
                return Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                // 继续找
            }
        }
        return 0;
    }

    // ===================== 通用 =====================

    /** 读取 ZIP 中匹配的条目（只保留小文件，避免解压炸弹）。 */
    private static Map<String, byte[]> readZip(byte[] bytes, java.util.function.Predicate<String> filter)
            throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !filter.test(entry.getName())) {
                    continue;
                }
                byte[] content = zip.readAllBytes();
                if (content.length > MAX_INPUT_BYTES) {
                    continue;
                }
                result.put(entry.getName(), content);
            }
        }
        return result;
    }

    /** 安全解析 XML（禁 DOCTYPE / 外部实体）。 */
    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml));
    }

    /** 取元素下所有指定标签（按出现顺序）的文本并拼接。 */
    private static String textOf(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String value = node.getTextContent();
            if (value != null && !value.isEmpty()) {
                text.append(value);
            }
        }
        return text.toString();
    }

    /** 只取直接子元素（xlsx 的 `<v>` 在 `<c>` 下）。 */
    private static String childText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                String value = child.getTextContent();
                return value == null ? "" : value;
            }
        }
        return "";
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n…（超出上限，已截断）";
    }

    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 供调用方把抽取结果写成 sidecar 文件名。 */
    public static String sidecarName(String fileName) {
        return fileName + ".txt";
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
