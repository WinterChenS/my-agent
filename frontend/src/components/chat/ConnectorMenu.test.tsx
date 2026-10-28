import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import type { ConnectorInfo } from "@/api/connectors";
import { ConnectorMenu } from "./ConnectorMenu";

/**
 * 输入框下方的连接器入口：**只显示绑定到当前 Agent 的连接器**，
 * 并且开关能直接改启用状态（这是用户最常用的动作）。
 */
const listConnectors = vi.fn();
const setConnectorEnabled = vi.fn();

vi.mock("@/api/connectors", () => ({
  listConnectors: () => listConnectors(),
  setConnectorEnabled: (id: number, enabled: boolean) => setConnectorEnabled(id, enabled),
}));

function connector(id: number, overrides: Partial<ConnectorInfo> = {}): ConnectorInfo {
  return {
    id,
    type: "MAIL",
    typeName: "邮件",
    name: `邮箱${id}`,
    alias: null,
    account: "me@company.com",
    enabled: true,
    status: "OK",
    lastError: null,
    lastCheckedAt: null,
    inboundMode: "OFF",
    capabilities: ["READ", "SEND"],
    config: {},
    agentIds: [],
    ...overrides,
  };
}

function setup(agentId: number | null) {
  render(
    <MemoryRouter>
      <ConnectorMenu agentId={agentId} />
    </MemoryRouter>,
  );
}

async function openMenu() {
  fireEvent.click(screen.getByTestId("connector-menu-button"));
  await waitFor(() => expect(screen.getByText("连接器")).toBeTruthy());
}

afterEach(cleanup);
beforeEach(() => vi.clearAllMocks());

describe("ConnectorMenu", () => {
  it("只显示绑定到当前 Agent 的连接器", async () => {
    listConnectors.mockResolvedValue([
      connector(1, { name: "工作邮箱", agentIds: [7, 9] }),
      connector(2, { name: "别的邮箱", agentIds: [9] }),
    ]);
    setup(7);
    await openMenu();

    expect(await screen.findByText("工作邮箱")).toBeTruthy();
    expect(screen.queryByText("别的邮箱")).toBeNull();
  });

  it("没绑定任何连接器时给出可操作的引导", async () => {
    listConnectors.mockResolvedValue([connector(1, { agentIds: [99] })]);
    setup(7);
    await openMenu();

    await waitFor(() =>
      expect(screen.getByText(/当前 Agent 还没有绑定连接器/)).toBeTruthy(),
    );
    expect(screen.getByText(/去「能力 → 连接器」/)).toBeTruthy();
  });

  it("没有选中 Agent 时不列任何东西", async () => {
    listConnectors.mockResolvedValue([connector(1, { agentIds: [] })]);
    setup(null);
    await openMenu();

    expect(await screen.findByText("先选择一个 Agent。")).toBeTruthy();
  });

  it("开关能停用/启用连接器", async () => {
    listConnectors.mockResolvedValue([connector(5, { name: "QQ邮箱", agentIds: [7] })]);
    setConnectorEnabled.mockResolvedValue(connector(5, { name: "QQ邮箱", enabled: false }));

    setup(7);
    await openMenu();

    const checkbox = (await screen.findByLabelText("启用 QQ邮箱")) as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
    fireEvent.click(checkbox);

    await waitFor(() => expect(setConnectorEnabled).toHaveBeenCalledWith(5, false));
    await waitFor(() => expect((screen.getByLabelText("启用 QQ邮箱") as HTMLInputElement).checked).toBe(false));
  });

  it("切换失败会回滚开关并提示", async () => {
    listConnectors.mockResolvedValue([connector(6, { name: "QQ邮箱", agentIds: [7] })]);
    setConnectorEnabled.mockRejectedValue(new Error("只能操作自己的连接器"));

    setup(7);
    await openMenu();

    const checkbox = (await screen.findByLabelText("启用 QQ邮箱")) as HTMLInputElement;
    fireEvent.click(checkbox);

    await waitFor(() => expect(setConnectorEnabled).toHaveBeenCalledWith(6, false));
    await waitFor(() => expect((screen.getByLabelText("启用 QQ邮箱") as HTMLInputElement).checked).toBe(true));
  });

  it("显示已启用数量", async () => {
    listConnectors.mockResolvedValue([
      connector(1, { name: "甲", agentIds: [7], enabled: true }),
      connector(2, { name: "乙", agentIds: [7], enabled: false, status: "UNKNOWN" }),
    ]);
    setup(7);
    await openMenu();

    expect(await screen.findByText("已启用 1/2")).toBeTruthy();
  });
});
