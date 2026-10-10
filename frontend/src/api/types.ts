/** 后端统一响应体（技术方案第 5 节）。 */
export interface Result<T> {
  code: number;
  msg: string;
  data: T;
}

/** 分页响应。 */
export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

export interface UserInfo {
  id: number;
  username: string;
  displayName?: string | null;
  role: "ADMIN" | "USER";
}

export interface LoginResponse {
  token: string;
  user: UserInfo;
}

export interface ChatSession {
  id: number;
  agentId: number | null;
  title: string | null;
  threadId: string | null;
  lastActiveAt: string | null;
  createdAt: string;
}

export interface ChatMessage {
  id: number;
  role: "USER" | "ASSISTANT" | "TOOL" | "SYSTEM" | string;
  content: string | null;
  aguiMeta?: Record<string, unknown> | null;
  seq: number | null;
  createdAt: string;
}

export interface AgentSummary {
  id: number;
  name: string;
  agentId: string;
  status: string;
}
