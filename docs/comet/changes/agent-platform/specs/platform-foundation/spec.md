# platform-foundation

## Purpose

提供可运行的前后端骨架、配置体系、持久化基线、统一响应契约与鉴权，作为其余能力的地基。

## 项目布局

```
backend/agent-server/     # 单模块 Maven，Java 21，Spring Boot 3.3.5
frontend/                 # Vite + React 18 + TS + Tailwind v4 + shadcn/ui
docs/                     # 技术方案、任务列表、Comet 产物
```

## 配置与环境

- `application.yml` 定义公共配置；`application-dev.yml` 定义内网开发环境（PostgreSQL / Redis 连接）。
- 中间件（内网，明文允许）：PostgreSQL 与 Redis 位于内网主机；库为 `agent_platform`（应用）与 `agent_platform_test`（集成测试）。
- Redis 逻辑库：`db10` 会话、`db11` 缓存；所有键统一前缀 `myagent:`，不得使用已被其他应用占用的 `db0`/`db1`。
- 环境变量：`JWT_SECRET`、`AGENT_CRYPTO_KEY`；dev profile 提供可用默认值，生产必须由环境注入。
- 前端 Vite 代理 `/api` 与 `/agui` → `http://localhost:8080`。
- `.gitignore` 覆盖 `target/`、`node_modules/`、`.env`；提交前 `git status` 必须干净。

## 持久化基线

- 迁移脚本仅允许位于 `backend/agent-server/src/main/resources/db/migration/`，使用 Flyway。
- Flyway 采用 11.7.2：需同时满足两点——能对 PostgreSQL 18（实测服务端 18.3）执行迁移，且保留 Spring Boot 3.3.5 `FlywayAutoConfiguration` 依赖的 API（Flyway 12 已移除该方法，会导致启动失败）。
- `V1__init.sql` 建立表：`sys_user`、`llm_provider`、`llm_model`、`skill`、`mcp_server`、`agent`、`agent_skill`、`agent_mcp_server`、`chat_session`、`chat_message`、`token_usage`、`approval_record`。
- 除关联表外每表含 `id BIGSERIAL PRIMARY KEY`、`created_at TIMESTAMPTZ`、`updated_at TIMESTAMPTZ`。
- `V2__seed.sql` 插入管理员 `admin`（BCrypt 哈希）与一条 `DISABLED` 状态的示例 provider/model。
- 应用代码中不得出现 `CREATE TABLE` / `ALTER TABLE`（门禁 G4）。
- `mvn flyway:info` 必须全部 success。

## 统一响应契约

- `Result<T>`：`{"code":0,"msg":"ok","data":...}`；`code` 非 0 表示业务错误。
- 分页 `PageResult`：`{"records":[...],"total":n,"current":n,"size":n}`。
- `BizException` 携带业务码；`GlobalExceptionHandler` 统一转换为 `Result`，不得向客户端抛裸 500 堆栈。
- 校验失败返回非 0 业务码并带字段说明。

## 鉴权与角色

- `POST /api/auth/login`：`{username,password}` → `data:{token, user:{id,username,role}}`；凭据错误返回 401。
- `GET /api/auth/me`：返回当前用户；缺失/无效 token 返回 401。
- JWT 使用 HS256，密钥来自 `JWT_SECRET`，通过 `Authorization: Bearer <token>` 传递。
- 密码使用 BCrypt 存储与校验。
- 角色 `ADMIN` / `USER`：配置类接口（provider/model/agent/skill/mcp-servers）仅 `ADMIN` 可写；审计接口 ADMIN 可查全员、USER 仅限本人。
- `GET /api/health` 返回 `{code:0,data:"up"}`，无需鉴权。
- `/api/auth/login` 与 `/api/health` 放行；`/agui/run` 需 JWT（前端以 fetch 注入 header，不使用 EventSource）。

## CryptoService

- `encrypt(String)` / `decrypt(String)` 使用 AES-GCM，主密钥来自 `AGENT_CRYPTO_KEY`。
- 加解密往返稳定；解密失败抛出可识别业务错误，不导致未捕获异常。
- 仅用于 api_key 等敏感字段；Redis / PostgreSQL 连接口令不加密。

## 边界

- Spring Boot 层不实现推理循环、工具调用或 MCP 协议；这些全部由 AgentScope 承担（门禁 G8）。

## 验收标准

- `mvn verify` 成功；`mvn dependency:tree` 中 AgentScope 坐标与方案一致（G1）。
- `curl /api/health` 返回 `{"code":0,...}`。
- 登录成功/失败、无 token 与有 token 访问 `/api/auth/me` 行为符合上述契约（附 curl 输出）。
- JwtService 签发/解析与 CryptoService 加解密单测往返通过。
- `mvn flyway:info` 全部 success；`sys_user` 存在 admin 行。
- 配置类包无 `io.agentscope` 引用（G2）。
