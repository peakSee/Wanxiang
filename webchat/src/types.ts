export type ConnectionStatus = "connecting" | "online" | "offline";
export type MobileSection = "chat" | "workspace";
export type ConversationMode = "normal";

export interface ConversationCreateTarget {
  mode: ConversationMode;
}

export interface Conversation {
  id: string | number;
  mode?: string;
  title?: string;
  summary?: string;
  lastMessage?: string;
  messageCount?: number;
  updatedAt?: number;
}

export interface Attachment {
  fileName: string;
  mimeType: string;
  size: number;
  dataUrl: string;
  isImage: boolean;
}

export interface ChatMessage {
  id?: number | string;
  contentId?: string;
  user?: number;
  type?: number;
  content?: unknown;
  streamMeta?: Record<string, unknown>;
  reasoning_content?: string;
  reasoningContent?: string;
  isError?: boolean;
  isLoading?: boolean;
  createAt?: number | string;
}

export interface WorkspaceInfo {
  rootPath?: string;
  [key: string]: unknown;
}

export interface WorkspaceItem {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
}

export interface WorkspaceListing {
  path?: string;
  items?: WorkspaceItem[];
}

export interface QuickPhrase {
  id: string;
  title: string;
  content: string;
  description?: string;
}

export interface BootstrapPayload {
  workspace?: {
    workspace?: WorkspaceInfo | null;
    root?: { path?: string } | null;
  } | null;
  quickPhrases?: QuickPhrase[];
}

export interface ApprovalRequest {
  id: string;
  toolName: string;
  argumentsJson: string;
  workspace: string;
  riskLevel: string;
  reason: string;
  summary: string;
  createdAt: number;
  expiresAt: number;
}

export interface RealtimeEventData {
  conversationId?: string | number;
  conversationMode?: string;
  mode?: string;
  messages?: ChatMessage[];
  kind?: string;
  taskId?: string;
  toolType?: string;
  approvals?: ApprovalRequest[];
  [key: string]: unknown;
}

export type RealtimeEventName =
  | "conversation_created"
  | "conversation_updated"
  | "conversation_deleted"
  | "messages_replaced"
  | "chat_task_event"
  | "workspace_changed";

export interface RunResult {
  taskId?: string | number;
  turnId?: string;
  conversationMode?: ConversationMode;
  conversation?: Conversation;
}

export interface ApprovalResult {
  accepted: boolean;
  taskId?: string;
}

export interface WorkspaceFilePayload {
  content?: string;
}
