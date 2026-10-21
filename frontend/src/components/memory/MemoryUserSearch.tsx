import { useEffect, useRef, useState } from "react";
import { Search, X } from "lucide-react";
import { listUsers, type UserRow } from "@/api/users";

/**
 * 按**用户名或用户 ID**精确搜索一个用户（记忆页给超管用）。
 *
 * <p>为什么不是下拉框：用户一多，下拉框要么只装得下前 N 个（“怎么只有我自己/内部用户/外部用户？”），
 * 要么得连库翻页找 —— 而排查问题的人手里通常只有“用户 #37”这种 id（日志/审计/工单里都是 id）。
 * 搜索框支持两种输入：`张三` / `zhangsan`（模糊匹配用户名、显示名）、`37` 或 `#37`（精确匹配 ID，
 * 后端 `UserService.list` 里对纯数字关键字额外加了 `id = ?`）。
 *
 * <p>允许“空结果”：搜不到就明说没找到，不要假装选中了什么。
 */
interface Props {
  /** 当前正在看谁的记忆；null = 自己 */
  selected: UserRow | null;
  onSelect: (user: UserRow | null) => void;
}

export function MemoryUserSearch({ selected, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<UserRow[]>([]);
  const [open, setOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  // 输入即搜（250ms 防抖）：每敲一个字都打一次接口会把用户列表接口打爆
  useEffect(() => {
    const keyword = query.trim();
    if (!keyword) {
      setResults([]);
      setSearching(false);
      return;
    }
    let cancelled = false;
    setSearching(true);
    const timer = window.setTimeout(() => {
      listUsers({ keyword, size: 8 })
        .then((page) => {
          if (cancelled) return;
          setResults(page.records);
          setOpen(true);
        })
        .catch(() => {
          if (!cancelled) setResults([]);
        })
        .finally(() => {
          if (!cancelled) setSearching(false);
        });
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [query]);

  // 点空白处收起结果（结果浮在内容上方，不收起会挡住清单）
  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  function pick(user: UserRow | null) {
    onSelect(user);
    setQuery("");
    setResults([]);
    setOpen(false);
  }

  return (
    <div className="relative flex items-center gap-2" ref={boxRef} data-testid="memory-user-search">
      {selected && (
        <span
          className="border-input flex items-center gap-1 rounded-md border px-2 py-1 text-xs"
          data-testid="memory-user-current"
        >
          <span className="max-w-32 truncate">{selected.displayName || selected.username}</span>
          <span className="text-muted-foreground">#{selected.id}</span>
          <button
            type="button"
            data-testid="memory-user-clear"
            aria-label="回到我自己"
            title="回到我自己"
            className="hover:text-foreground text-muted-foreground"
            onClick={() => pick(null)}
          >
            <X className="size-3" />
          </button>
        </span>
      )}
      <div className="relative">
        <Search className="text-muted-foreground pointer-events-none absolute top-1/2 left-2 size-3.5 -translate-y-1/2" />
        <input
          data-testid="memory-user-search-input"
          aria-label="按用户名或 ID 搜索用户"
          className="border-input h-7 w-52 rounded-md border bg-transparent pr-2 pl-7 text-xs"
          placeholder="搜用户（用户名 / ID）"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onFocus={() => {
            if (query.trim()) setOpen(true);
          }}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              setOpen(false);
            }
            if (event.key === "Enter" && results.length > 0) {
              event.preventDefault();
              pick(results[0]);
            }
          }}
        />
      </div>

      {open && query.trim() !== "" && (
        <ul
          data-testid="memory-user-results"
          className="bg-popover absolute top-full right-0 z-30 mt-1 max-h-72 w-72 overflow-y-auto rounded-md border shadow-md"
        >
          {searching && results.length === 0 && (
            <li className="text-muted-foreground px-2 py-2 text-xs">搜索中…</li>
          )}
          {!searching && results.length === 0 && (
            <li className="text-muted-foreground px-2 py-2 text-xs" data-testid="memory-user-empty">
              没找到匹配的用户（可试用户名、显示名或用户 ID）
            </li>
          )}
          {results.map((user) => (
            <li key={user.id}>
              <button
                type="button"
                data-testid="memory-user-result"
                onClick={() => pick(user)}
                className="hover:bg-accent flex w-full items-center gap-2 px-2 py-1.5 text-left text-xs"
              >
                <span className="min-w-0 flex-1 truncate">
                  {user.displayName || user.username}
                  <span className="text-muted-foreground ml-1">{user.username}</span>
                </span>
                <span className="text-muted-foreground shrink-0 text-[11px]">#{user.id}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
