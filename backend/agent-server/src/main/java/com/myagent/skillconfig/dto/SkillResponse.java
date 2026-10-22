package com.myagent.skillconfig.dto;

import java.time.OffsetDateTime;

/** Skill 响应。 */
public record SkillResponse(
        Long id,
        String name,
        String description,
        String content,
        Integer version,
        String status,
        /** 导入来源（仓库地址）；手工创建时为 null。 */
        String sourceUrl,
        /** 导入来源的分支/标签。 */
        String sourceRef,
        /** 资源文件数量（标准 Skill 的 scripts/ references/ assets/ …）。 */
        int resourceCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
