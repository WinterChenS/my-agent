import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * 连接器页：表单字段**来自后端类型描述**（加一种连接器不用改前端），
 * 以及「未测试 / 正常 / 异常」要区分显示。
 */
const listConnectorTypes = vi.fn();
const listConnectors = vi.fn();
const createConnector = vi.fn();
const updateConnector = vi.fn();
const testConnector = vi.fn();
const bindConnectorAgents = vi.fn();
const listConnectorActions = vi.fn();
const deleteConnector = vi.fn();

vi.mock("@/api/connectors", () => ({
  listConnectorTypes: () => listConnectorTypes(),
  listConnectors: () => listConnectors(),
  createConnector: (payload: unknown) => createConnector(payload),
  updateConnector: (id: number, payload: unknown) => updateConnector(id, payload),
  testConnector: (id: number) => testConnector(id),
  bindConnectorAgents: (id: number, ids: number[]) => bindConnectorAgents(id, ids),
  listConnectorActions: (id: number) => listConnectorActions(id),
  deleteConnector: (id: number) => deleteConnector(id),
}));

vi.mock("@/api/agents", () => ({
  listAgentDetails: () => Promise.resolve({ records: [{ id: 3, name: "小梅", permissionMode: "ALWAYS_ALLOW" }] }),
}));

const MAIL_TYPE = {
  type: "MAIL",
  displayName: "邮件",
  description: "让 Agent 读取、搜索你的邮箱并代你发信",
  fields: [
    {
      key: "email", label: "邮箱地址", secret: false, hint: "要连接的邮箱账号", required: true,
      placeholder: "me@company.com", type: "TEXT", options: [],
    },
    {
      key: "imapPort", label: "IMAP 端口", secret: false, hint: "SSL 一般是 993", required: true,
      placeholder: "993", type: "NUMBER", options: [],
    },
    {
      key: "imapSsl", label: "IMAP 使用 SSL", secret: false, hint: "993 选是", required: false,
      placeholder: "", type: "SELECT",
      options: [
        { value: "true", label: "是" },
        { value: "false", label: "否" },
      ],
    },
    {
      key: "password", label: "密码 / 授权码", secret: true, hint: "修改时留空表示不改", required: true,
      placeholder: "", type: "PASSWORD", options: [],
    },
  ],
  requiredKeys: ["email", "password"],
  secretKeys: ["password"],
  capabilities: ["READ", "SEND"],
  inboundSupported: true,
  available: true,
};

const CONNECTOR = {
  id: 5,
  type: "MAIL",
  typeName: "邮件",
  name: "工作邮箱",
  alias: "work",
  account: "me@company.com",
  enabled: true,
  status: "ERROR" as const,
  lastError: "IMAP 登录失败：授权码错误",
  lastCheckedAt: null,
  inboundMode: "NOTIFY" as const,
  capabilities: ["READ", "SEND"],
  config: { email: "me@company.com", password: "***" },
  agentIds: [3],
};

describe("ConnectorsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listConnectorTypes.mockResolvedValue([MAIL_TYPE]);
    listConnectors.mockResolvedValue([CONNECTOR]);
  });

  it("按后端类型描述渲染表单字段（含密钥输入不留明文）", async () => {
    const ConnectorsPage = (await import("@/pages/admin/connectors/ConnectorsPage")).default;
    render(<ConnectorsPage />);

    await waitFor(() => expect(screen.getByText("我的连接器（1）")).toBeTruthy());
    fireEvent.click(screen.getAllByRole("button", { name: /添加/ })[0]);

    expect(await screen.findByText("添加邮件连接器")).toBeTruthy();
    const password = screen.getByPlaceholderText("保持不变（留空表示不修改）");
    expect(password.getAttribute("type")).toBe("password");
  });

  it("新建时提交的配置来自表单输入", async () => {
    createConnector.mockResolvedValue(CONNECTOR);
    const ConnectorsPage = (await import("@/pages/admin/connectors/ConnectorsPage")).default;
    render(<ConnectorsPage />);

    await waitFor(() => expect(listConnectorTypes).toHaveBeenCalled());
    fireEvent.click(screen.getAllByRole("button", { name: /添加/ })[0]);

    const nameInput = await screen.findByPlaceholderText("工作邮箱");
    fireEvent.change(nameInput, { target: { value: "个人邮箱" } });
    fireEvent.change(screen.getByPlaceholderText("me@company.com"), { target: { value: "me@company.com" } });
    fireEvent.change(screen.getByPlaceholderText("保持不变（留空表示不修改）"), { target: { value: "授权码" } });

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(createConnector).toHaveBeenCalled());
    expect(createConnector.mock.calls[0][0]).toMatchObject({
      type: "MAIL",
      name: "个人邮箱",
      config: { email: "me@company.com", password: "授权码" },
    });
  });

  it("枚举字段渲染成下拉、端口渲染成数字输入（不让用户手打 true/993）", async () => {
    const ConnectorsPage = (await import("@/pages/admin/connectors/ConnectorsPage")).default;
    render(<ConnectorsPage />);

    await waitFor(() => expect(screen.getByText("我的连接器（1）")).toBeTruthy());
    fireEvent.click(screen.getAllByRole("button", { name: /添加/ })[0]);

    const ssl = (await screen.findByTestId("connector-field-imapSsl")) as HTMLSelectElement;
    expect(ssl.tagName).toBe("SELECT");
    expect(ssl.value).toBe("true");
    expect(Array.from(ssl.options).map((option) => option.label)).toEqual(["是", "否"]);

    const port = screen.getByTestId("connector-field-imapPort") as HTMLInputElement;
    expect(port.type).toBe("number");
  });

  it("状态异常时显示后端给的人话错误", async () => {
    const ConnectorsPage = (await import("@/pages/admin/connectors/ConnectorsPage")).default;
    render(<ConnectorsPage />);

    await waitFor(() => expect(screen.getByText("连接异常")).toBeTruthy());
    expect(screen.getByText("IMAP 登录失败：授权码错误")).toBeTruthy();
    expect(screen.getByText("推 IM 通知")).toBeTruthy();
    expect(screen.getByText("小梅")).toBeTruthy();
  });

  it("测试连接失败时用后端消息提示", async () => {
    testConnector.mockResolvedValue({ ok: false, message: "SMTP 登录失败：账号或授权码错误", steps: [] });
    const ConnectorsPage = (await import("@/pages/admin/connectors/ConnectorsPage")).default;
    render(<ConnectorsPage />);

    await waitFor(() => expect(screen.getByText("工作邮箱")).toBeTruthy());
    const buttons = screen.getAllByRole("button");
    const testButton = buttons.find((button) => button.getAttribute("title") === "测试连接");
    fireEvent.click(testButton!);

    await waitFor(() => expect(testConnector).toHaveBeenCalledWith(5));
  });

  it("绑定对话框把已绑定的 Agent 勾上并整体提交", async () => {
    bindConnectorAgents.mockResolvedValue([3]);
    const ConnectorsPage = (await import("@/pages/admin/connectors/ConnectorsPage")).default;
    render(<ConnectorsPage />);

    await waitFor(() => expect(screen.getByText("工作邮箱")).toBeTruthy());
    const bindButton = screen
      .getAllByRole("button")
      .find((button) => button.getAttribute("title") === "绑定 Agent");
    fireEvent.click(bindButton!);

    expect(await screen.findByText("绑定「工作邮箱」到 Agent")).toBeTruthy();
    const checkbox = screen.getByRole("checkbox") as HTMLInputElement;
    expect(checkbox.checked).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "保存绑定" }));
    await waitFor(() => expect(bindConnectorAgents).toHaveBeenCalledWith(5, [3]));
  });
});
