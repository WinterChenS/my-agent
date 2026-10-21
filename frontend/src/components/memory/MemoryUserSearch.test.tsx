import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { UserRow } from "@/api/users";
import { MemoryUserSearch } from "./MemoryUserSearch";

/**
 * 记忆页的用户搜索：**按用户名或 ID 精确找人**。
 *
 * <p>这里钉住四件事：①输入即搜（带防抖，不是一上来就把全量用户列出来）；
 * ②点结果会把选中的用户交回去并清空输入；③搜不到要明说；④能被清掉（回到“我自己”）。
 */
const searchMock = vi.fn();

vi.mock("@/api/users", () => ({
  listUsers: (query: { keyword?: string; size?: number }) => searchMock(query),
}));

function user(id: number, username: string, displayName: string | null): UserRow {
  return { id, username, displayName, role: "USER", status: "ENABLED" };
}

function page(records: UserRow[]) {
  return Promise.resolve({ records, total: records.length, current: 1, size: 8 });
}

afterEach(() => {
  cleanup();
  searchMock.mockReset();
});

describe("MemoryUserSearch", () => {
  it("输入关键字才搜，且带防抖（连敲不逐个打接口）", async () => {
    searchMock.mockReturnValue(page([user(37, "zhangsan", "张三")]));
    render(<MemoryUserSearch selected={null} onSelect={vi.fn()} />);

    // 还没输入时不该有任何请求（旧实现就是“一上来拉 200 个用户”）
    expect(searchMock).not.toHaveBeenCalled();

    const input = screen.getByTestId("memory-user-search-input");
    fireEvent.change(input, { target: { value: "张" } });
    fireEvent.change(input, { target: { value: "张三" } });

    const result = await screen.findByTestId("memory-user-result");
    expect(result.textContent).toContain("张三");
    expect(result.textContent).toContain("#37");
    // 两次输入被 250ms 防抖合并成一次请求
    expect(searchMock).toHaveBeenCalledTimes(1);
    expect(searchMock.mock.calls[0][0]).toMatchObject({ keyword: "张三" });
  });

  it("点结果：把用户交回去、清空输入、收起结果", async () => {
    searchMock.mockReturnValue(page([user(37, "zhangsan", "张三")]));
    const onSelect = vi.fn();
    render(<MemoryUserSearch selected={null} onSelect={onSelect} />);

    fireEvent.change(screen.getByTestId("memory-user-search-input"), {
      target: { value: "37" },
    });
    fireEvent.click(await screen.findByTestId("memory-user-result"));

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 37 }));
    expect(screen.getByTestId("memory-user-search-input")).toHaveProperty("value", "");
    await waitFor(() => expect(screen.queryByTestId("memory-user-results")).toBeNull());
  });

  it("搜不到就说没找到（不假装选中）", async () => {
    searchMock.mockReturnValue(page([]));
    render(<MemoryUserSearch selected={null} onSelect={vi.fn()} />);

    fireEvent.change(screen.getByTestId("memory-user-search-input"), {
      target: { value: "谁都不叫这个名字" },
    });
    expect(await screen.findByTestId("memory-user-empty")).toBeTruthy();
    expect(screen.queryByTestId("memory-user-result")).toBeNull();
  });

  it("有选中用户时显示是谁，并能一键回到自己", () => {
    const onSelect = vi.fn();
    render(
      <MemoryUserSearch selected={user(37, "zhangsan", "张三")} onSelect={onSelect} />,
    );

    expect(screen.getByTestId("memory-user-current").textContent).toContain("张三");
    expect(screen.getByTestId("memory-user-current").textContent).toContain("#37");

    fireEvent.click(screen.getByTestId("memory-user-clear"));
    expect(onSelect).toHaveBeenCalledWith(null);
  });
});
