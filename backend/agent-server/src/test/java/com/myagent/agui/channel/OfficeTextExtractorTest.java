package com.myagent.agui.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
