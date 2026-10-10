package com.myagent.agentconfig.controller;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.dto.AgentResponse;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.common.PageResult;
import com.myagent.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agent 配置。 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentConfigService service;

    public AgentController(AgentConfigService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<AgentResponse>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(service.list(current, size));
    }

    @GetMapping("/{id}")
    public Result<AgentResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    public Result<AgentResponse> create(@Valid @RequestBody AgentRequest request) {
        return Result.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public Result<AgentResponse> update(
            @PathVariable Long id, @Valid @RequestBody AgentRequest request) {
        return Result.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
