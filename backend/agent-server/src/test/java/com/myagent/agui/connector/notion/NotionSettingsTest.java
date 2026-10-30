package com.myagent.agui.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Notion 配置解析：id 归一化（用户常直接粘链接）、白名单、写入开关。 */
class NotionSettingsTest {

    private static Map<String, String> config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("token", "ntn_test");
        return config;
    }

    @Test
    void 默认允许写入_块数上限200_白名单为空表示不限() {
        NotionSettings settings = NotionSettings.of(config());
        assertThat(settings.allowWrite()).isTrue();
        assertThat(settings.maxBlocksPerPage()).isEqualTo(200);
        assertThat(settings.allowedPageIds()).isEmpty();
        assertThat(settings.allowsPage("2f1b8c9d4e5f4a1b8c9d4e5f4a1b8c9d")).isTrue();
    }

    @Test
    void 关闭写入与自定义上限() {
        Map<String, String> config = config();
        config.put("allowWrite", "false");
        config.put("maxBlocksPerPage", "50");
        NotionSettings settings = NotionSettings.of(config);
        assertThat(settings.allowWrite()).isFalse();
        assertThat(settings.maxBlocksPerPage()).isEqualTo(50);
    }

    @Test
    void 页面链接能提取出标准id() {
        String id = NotionSettings.NotionIds.normalizeId(
                "https://www.notion.so/myworkspace/Weekly-Notes-2f1b8c9d4e5f4a1b8c9d4e5f4a1b8c9d?pvs=4");
        assertThat(id).isEqualTo("2f1b8c9d-4e5f-4a1b-8c9d-4e5f4a1b8c9d");
        assertThat(NotionSettings.NotionIds.normalizeId("2f1b8c9d4e5f4a1b8c9d4e5f4a1b8c9d"))
                .isEqualTo("2f1b8c9d-4e5f-4a1b-8c9d-4e5f4a1b8c9d");
        assertThat(NotionSettings.NotionIds.normalizeId("2F1B8C9D-4E5F-4A1B-8C9D-4E5F4A1B8C9D"))
                .isEqualTo("2f1b8c9d-4e5f-4a1b-8c9d-4e5f4a1b8c9d");
        assertThat(NotionSettings.NotionIds.normalizeId("随便写的")).isNull();
        assertThat(NotionSettings.NotionIds.normalizeId(null)).isNull();
    }

    @Test
    void 白名单支持直接粘链接() {
        Map<String, String> config = config();
        config.put("allowedPageIds",
                "https://www.notion.so/x/Page-2f1b8c9d4e5f4a1b8c9d4e5f4a1b8c9d, 11111111222233334444555566667777");
        NotionSettings settings = NotionSettings.of(config);

        assertThat(settings.allowsPage("2f1b8c9d-4e5f-4a1b-8c9d-4e5f4a1b8c9d")).isTrue();
        assertThat(settings.allowsPage("11111111-2222-3333-4444-555566667777")).isTrue();
        assertThat(settings.allowsPage("99999999-2222-3333-4444-555566667777")).isFalse();
    }

    @Test
    void 数据库白名单独立于页面白名单() {
        Map<String, String> config = config();
        config.put("allowedDataSources", "aaaaaaaa222233334444555566667777");
        NotionSettings settings = NotionSettings.of(config);

        assertThat(settings.allowsDataSource("aaaaaaaa-2222-3333-4444-555566667777")).isTrue();
        assertThat(settings.allowsPage("aaaaaaaa-2222-3333-4444-555566667777")).isTrue(); // 页面白名单为空=不限
    }
}
