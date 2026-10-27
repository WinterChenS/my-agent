package com.myagent.agui.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Office 文本抽取：xlsx/docx/pptx → 纯文本（零新依赖，靠 ZIP + XML）。
 *
 * <p>背景：钉钉里发来 Excel，文件确实存进工作区了，但模型读二进制只会看到乱码。
 */
class OfficeTextExtractorTest {

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * 造一个「本地头里 size=0」的 ZIP（WPS 等工具的流式写法）。
     *
     * <p>这正是用户实测踩到的包：{@code ZipInputStream} 会抛
     * {@code ZipException: invalid entry size (expected 0 but got N)}，必须走 ZipFile。
     */
    private static byte[] streamingZip(Map<String, String> entries) throws Exception {
        java.io.ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.util.List<byte[]> central = new java.util.ArrayList<>();
        java.util.List<Integer> offsets = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] data = entry.getValue().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(data);
            int offset = out.size();
            offsets.add(offset);
            // local header：size/compSize 故意写 0，且不带 data descriptor
            ByteBuffer head = ByteBuffer.allocate(30 + name.length).order(ByteOrder.LITTLE_ENDIAN);
            head.putInt(0x04034b50).putShort((short) 10).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt((int) crc.getValue())
                    .putInt(0).putInt(0).putShort((short) name.length).putShort((short) 0).put(name);
            out.write(head.array());
            out.write(data);
            ByteBuffer cd = ByteBuffer.allocate(46 + name.length).order(ByteOrder.LITTLE_ENDIAN);
            cd.putInt(0x02014b50).putShort((short) 10).putShort((short) 10).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putInt((int) crc.getValue()).putInt(data.length).putInt(data.length)
                    .putShort((short) name.length).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset).put(name);
            central.add(cd.array());
        }
        int cdStart = out.size();
        for (byte[] cd : central) {
            out.write(cd);
        }
        int cdSize = out.size() - cdStart;
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) central.size()).putShort((short) central.size())
                .putInt(cdSize).putInt(cdStart).putShort((short) 0);
        out.write(eocd.array());
        return out.toByteArray();
    }

    @Test
    void 流式写入的xlsx_本地头size为0_也能解析() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("xl/sharedStrings.xml", "<sst><si><t>商品</t></si></sst>");
        files.put("xl/worksheets/sheet1.xml",
                "<worksheet><sheetData><row><c t=\"s\"><v>0</v></c><c><v>9.9</v></c></row></sheetData></worksheet>");

        Optional<OfficeTextExtractor.Extracted> result =
                OfficeTextExtractor.extract("wps.xlsx", streamingZip(files));

        assertThat(result).isPresent();
        assertThat(result.get().text()).contains("商品	9.9");
    }

    @Test
    void xlsx按行抽取共享字符串与数字() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("xl/sharedStrings.xml",
                "<sst><si><t>产品</t></si><si><t>价格</t></si><si><t>苹果</t></si></sst>");
        files.put("xl/worksheets/sheet1.xml",
                "<worksheet><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>12.5</v></c></row>"
                        + "</sheetData></worksheet>");
        byte[] bytes = zip(files);

        Optional<OfficeTextExtractor.Extracted> result =
                OfficeTextExtractor.extract("价格表.xlsx", bytes);

        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo("excel");
        assertThat(result.get().text()).contains("产品\t价格").contains("苹果\t12.5");
    }

    @Test
    void xlsx的富文本单元格会拼接() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("xl/sharedStrings.xml",
                "<sst><si><r><t>前</t></r><r><t>后</t></r></si></sst>");
        files.put("xl/worksheets/sheet1.xml",
                "<worksheet><sheetData><row><c t=\"s\"><v>0</v></c></row></sheetData></worksheet>");

        Optional<OfficeTextExtractor.Extracted> result =
                OfficeTextExtractor.extract("a.xlsx", zip(files));

        assertThat(result).isPresent();
        assertThat(result.get().text()).contains("前后");
    }

    @Test
    void docx按段落抽取() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("word/document.xml",
                "<w:document><w:body>"
                        + "<w:p><w:r><w:t>第一段</w:t></w:r></w:p>"
                        + "<w:p><w:r><w:t>第二段</w:t></w:r></w:p>"
                        + "</w:body></w:document>");

        Optional<OfficeTextExtractor.Extracted> result =
                OfficeTextExtractor.extract("报告.docx", zip(files));

        assertThat(result).isPresent();
        assertThat(result.get().kind()).isEqualTo("word");
        assertThat(result.get().text()).contains("第一段").contains("第二段");
    }

    @Test
    void pptx按页抽取() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ppt/slides/slide2.xml", "<p:sld><a:t>第二页内容</a:t></p:sld>");
        files.put("ppt/slides/slide1.xml", "<p:sld><a:t>第一页内容</a:t></p:sld>");

        Optional<OfficeTextExtractor.Extracted> result =
                OfficeTextExtractor.extract("演示.pptx", zip(files));

        assertThat(result).isPresent();
        assertThat(result.get().text().indexOf("第一页内容"))
                .isLessThan(result.get().text().indexOf("第二页内容"));
    }

    @Test
    void 不支持的类型与损坏文件返回空() {
        assertThat(OfficeTextExtractor.extract("a.pdf", new byte[] {1, 2, 3})).isEmpty();
        assertThat(OfficeTextExtractor.extract("a.xlsx", new byte[] {1, 2, 3})).isEmpty();
        assertThat(OfficeTextExtractor.extract("a.xlsx", new byte[0])).isEmpty();
    }

    @Test
    void 类型判断与旁挂文件名() {
        assertThat(OfficeTextExtractor.supports("a.XLSX")).isTrue();
        assertThat(OfficeTextExtractor.supports("a.docx")).isTrue();
        assertThat(OfficeTextExtractor.supports("a.csv")).isFalse();
        assertThat(OfficeTextExtractor.sidecarName("价格表.xlsx")).isEqualTo("价格表.xlsx.txt");
    }
}
