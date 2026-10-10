package com.myagent.skillconfig.dto;

import jakarta.validation.constraints.NotBlank;

/** Skill 新建/更新请求。 */
public record SkillRequest(
        @NotBlank(message = "name 不能为空") String name,
        String description,
        String content,
        String status) {}
