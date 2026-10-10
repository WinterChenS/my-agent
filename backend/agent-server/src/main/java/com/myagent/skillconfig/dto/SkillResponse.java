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
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
