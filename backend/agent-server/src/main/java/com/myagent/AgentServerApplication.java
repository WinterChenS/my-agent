package com.myagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * my-agent 平台后端入口。
 *
 * <p>职责边界：本层只做配置 CRUD、鉴权、会话、审计落库，以及把数据库配置翻译成 AgentScope 对象；
 * 推理循环、工具调用与 MCP 协议全部由 AgentScope 承担。
 */
@SpringBootApplication
public class AgentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServerApplication.class, args);
    }
}
