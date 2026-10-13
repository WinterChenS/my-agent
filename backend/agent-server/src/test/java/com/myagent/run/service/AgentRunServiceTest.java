package com.myagent.run.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** tier1-upgrades ③：run 落库的纯逻辑（摘要截断）。 */
class AgentRunServiceTest {

    @Test
    void 摘要为空的输入返回null() {
        assertNull(AgentRunService.summarize(null));
        assertNull(AgentRunService.summarize("   "));
    }

    @Test
    void 短文本原样保留且换行压平() {
        assertEquals("你好 世界", AgentRunService.summarize("你好\n世界"));
    }

    @Test
    void 长文本按上限截断并加省略号() {
        String longText = "字".repeat(500);
        String summary = AgentRunService.summarize(longText);
        assertTrue(summary.endsWith("…"));
        assertEquals(201, summary.length());
    }
}
