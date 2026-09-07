import { useMemo, useState } from "react";
import { conversationKey, relativeDate } from "../format";
import type { ConnectionStatus, Conversation, ConversationCreateTarget } from "../types";
import { Icon } from "./Icon";

interface ConversationSidebarProps {
  conversations: Conversation[];
  selected: Conversation | null;
  connectionStatus: ConnectionStatus;
  onCreate: (target: ConversationCreateTarget) => void;
  onSelect: (conversation: Conversation) => void;
  onDelete: (conversation: Conversation) => Promise<void>;
}

const STATUS_LABELS: Record<ConnectionStatus, string> = {
  online: "已连接万象",
  offline: "连接中断，正在重试",
  connecting: "正在连接实时消息",
};

export function ConversationSidebar({
  conversations,
  selected,
  connectionStatus,
  onCreate,
  onSelect,
  onDelete,
}: ConversationSidebarProps) {
  const [search, setSearch] = useState("");
  const query = search.trim().toLowerCase();
  const visible = useMemo(() => conversations.filter((conversation) => (
    !query || [conversation.title, conversation.summary, conversation.lastMessage]
      .some((value) => String(value ?? "").toLowerCase().includes(query))
  )), [conversations, query]);

  return (
    <aside className="conversation-pane">
      <div className="conversation-toolbar">
        <div className="search-field">
          <Icon name="search" size={17} />
          <input
            type="search"
            aria-label="搜索智枢会话"
            placeholder="搜索会话"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          {search && (
            <button className="search-clear" type="button" aria-label="清除搜索" onClick={() => setSearch("")}>
              <Icon name="x" size={14} />
            </button>
          )}
        </div>
        <button
          className="sidebar-round-button primary"
          type="button"
          aria-label="新建智枢任务"
          title="新建智枢任务"
          onClick={() => onCreate({ mode: "normal" })}
        >
          <Icon name="plus" size={18} />
        </button>
      </div>

      <div className="conversation-list" aria-live="polite">
        <div className="conversation-section-header">
          <Icon name="agent" size={14} />
          <span>智枢会话</span>
          <small>{visible.length}</small>
        </div>
        {!visible.length && (
          <div className="list-empty">
            <Icon name={query ? "search" : "agent"} size={24} />
            <strong>{query ? "没有找到相关会话" : "还没有会话"}</strong>
            <span>{query ? "换个关键词试试" : "点击右上角开始任务"}</span>
          </div>
        )}
        {visible.map((conversation) => {
          const active = conversationKey(conversation) === conversationKey(selected);
          return (
            <div className={`conversation-item-row${active ? " active" : ""}`} key={conversationKey(conversation)}>
              <button className="conversation-item" type="button" onClick={() => onSelect(conversation)}>
                <span className="conversation-item-heading">
                  <strong>{conversation.title || "新会话"}</strong>
                  <time>{relativeDate(conversation.updatedAt)}</time>
                </span>
              </button>
              <button
                className="conversation-delete-button"
                type="button"
                aria-label={`删除“${conversation.title || "新会话"}”`}
                title="删除会话"
                onClick={() => void onDelete(conversation)}
              >
                <Icon name="trash" size={14} />
              </button>
            </div>
          );
        })}
      </div>

      <footer className="connection-footer">
        <span className={`connection-dot ${connectionStatus === "connecting" ? "" : connectionStatus}`} />
        <span>{STATUS_LABELS[connectionStatus]}</span>
      </footer>
    </aside>
  );
}
