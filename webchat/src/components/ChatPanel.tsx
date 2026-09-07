import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { isRecord } from "../api";
import { formatBytes, markdownToHtml, messageContent, messageTime } from "../format";
import type { ApprovalRequest, Attachment, ChatMessage, Conversation, QuickPhrase } from "../types";
import {
  ComposerAttachmentIcon,
  ComposerSendIcon,
  ComposerStopIcon,
} from "./ComposerIcons";
import { Icon } from "./Icon";

interface ChatPanelProps {
  conversation: Conversation | null;
  messages: ChatMessage[];
  approvals: ApprovalRequest[];
  globalError: string;
  sending: boolean;
  activeTaskId: string | null;
  onOpenConversations: () => void;
  onDelete: () => void;
  onSend: (text: string, attachments: Attachment[]) => Promise<boolean>;
  onCancel: () => void;
  onResolveApproval: (requestId: string, approved: boolean) => void;
  onClearError: () => void;
  onAttachmentError: (error: unknown) => void;
  quickPhrases: QuickPhrase[];
}

const GREETING_WORDS = ["聊天", "执行", "构建", "探索", "规划", "总结", "检索", "记忆"];
const WORD_ROTATE_INTERVAL = 1800;
const WORD_SPIN_DURATION = 460;

/** 轮播展示智枢与万象运行时紧密相关的核心能力。 */
function SlotWordRotator({ words }: { words: string[] }) {
  const [current, setCurrent] = useState(() => Math.floor(Math.random() * words.length));
  const [previous, setPrevious] = useState<number | null>(null);
  const currentRef = useRef(current);
  currentRef.current = current;

  useEffect(() => {
    if (words.length <= 1) return undefined;
    const timer = window.setInterval(() => {
      let next = Math.floor(Math.random() * words.length);
      if (next === currentRef.current) next = (next + 1) % words.length;
      setPrevious(currentRef.current);
      setCurrent(next);
    }, WORD_ROTATE_INTERVAL);
    return () => window.clearInterval(timer);
  }, [words]);

  useEffect(() => {
    if (previous === null) return undefined;
    const timer = window.setTimeout(() => setPrevious(null), WORD_SPIN_DURATION);
    return () => window.clearTimeout(timer);
  }, [previous]);

  return (
    <span className="greeting-word">
      {/* 隐藏测量层: 容器宽度始终取最长词, 避免换词时抖动 */}
      {words.map((word) => (
        <span className="word-sizer" aria-hidden="true" key={word}>{word}</span>
      ))}
      {previous !== null && (
        <span className="word-out" aria-hidden="true">{words[previous]}</span>
      )}
      <span className={previous !== null ? "word-in" : "word-current"}>{words[current]}</span>
    </span>
  );
}

function EmptyGreeting() {
  return (
    <div className="empty-state">
      <div className="empty-greeting">
        <p>你好👋，这里是万象智枢</p>
        <p>可以与你一起 <SlotWordRotator words={GREETING_WORDS} /></p>
      </div>
    </div>
  );
}

function fileToAttachment(file: File): Promise<Attachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve({
      fileName: file.name,
      mimeType: file.type || "application/octet-stream",
      size: file.size,
      dataUrl: String(reader.result),
      isImage: file.type.startsWith("image/"),
    });
    reader.onerror = () => reject(reader.error ?? new Error(`无法读取 ${file.name}`));
    reader.readAsDataURL(file);
  });
}

function attachmentName(attachment: Record<string, unknown>): string {
  return String(attachment.fileName ?? attachment.name ?? "附件");
}

function attachmentImage(attachment: Record<string, unknown>): string {
  const source = String(attachment.dataUrl ?? attachment.url ?? "");
  const mimeType = String(attachment.mimeType ?? attachment.type ?? "");
  const isImage = attachment.isImage === true
    || mimeType.startsWith("image/")
    || source.startsWith("data:image/")
    || /\.(avif|gif|jpe?g|png|webp)(?:[?#]|$)/i.test(source);
  return isImage && (source.startsWith("data:image/") || source.startsWith("https://") || source.startsWith("http://"))
    ? source
    : "";
}

function MessageAttachments({ attachments }: { attachments: Record<string, unknown>[] }) {
  if (!attachments.length) return null;
  return (
    <div className="message-attachments">
      {attachments.map((attachment, index) => {
        const image = attachmentImage(attachment);
        const name = attachmentName(attachment);
        return image ? (
          <img className="message-image" src={image} alt={name} key={`${name}-${index}`} />
        ) : (
          <span className="attachment-chip" key={`${name}-${index}`}>
            <Icon name="file" size={15} />
            <span>{name}</span>
          </span>
        );
      })}
    </div>
  );
}

function statusLabel(status: unknown): string {
  return ({
    running: "运行中",
    completed: "已完成",
    success: "已完成",
    error: "失败",
    timeout: "超时",
    interrupted: "已中断",
    cancelled: "已停止",
  } as Record<string, string>)[String(status)] ?? String(status || "已完成");
}

function statusClass(status: unknown): string {
  const value = String(status ?? "");
  if (value === "running") return "running";
  if (value === "success" || value === "completed") return "success";
  if (value === "error") return "error";
  if (value === "timeout") return "timeout";
  if (value === "interrupted" || value === "cancelled") return "interrupted";
  return "running";
}

function toolTypeLabel(card: Record<string, unknown>): string {
  const raw = `${String(card.toolType ?? "")} ${String(card.type ?? "")}`.toLowerCase();
  if (/terminal|shell|command|process/.test(raw)) return "终端";
  if (/browser|web|navigate/.test(raw)) return "网络工具";
  if (/search/.test(raw)) return "搜索";
  if (/file|read|write|edit/.test(raw)) return "文件";
  if (/subagent/.test(raw)) return "协同智能体";
  if (/mcp/.test(raw)) return "MCP";
  return "工具";
}

function toolIcon(card: Record<string, unknown>): "terminal" | "search" | "file" | "agent" | "workspace" {
  const raw = `${String(card.toolType ?? "")} ${String(card.type ?? "")}`.toLowerCase();
  if (/terminal|shell|command|process/.test(raw)) return "terminal";
  if (/browser|web|navigate/.test(raw)) return "search";
  if (/search/.test(raw)) return "search";
  if (/file|read|write|edit/.test(raw)) return "file";
  if (/subagent|agent|mcp/.test(raw)) return "agent";
  return "workspace";
}

function Message({
  message,
  active = false,
}: {
  message: ChatMessage;
  active?: boolean;
}) {
  const content = messageContent(message);
  const isUser = Number(message.user) === 1;
  const rawCard = isRecord(content.cardData) ? content.cardData : null;
  const card = rawCard ?? content;
  const attachments = Array.isArray(content.attachments)
    ? content.attachments.filter(isRecord)
    : [];
  const reasoning = String(message.reasoning_content ?? message.reasoningContent ?? "").trim();
  const classes = `message-row ${isUser ? "user" : "assistant"}${message.isError ? " error" : ""}`;
  const isCard = Number(message.type) === 2 || rawCard;

  // 万象 Harness 的工具调用、工具结果与能力事件。
  if (isCard) {
    const title = card.toolTitle ?? card.toolName ?? card.displayName ?? card.title ?? card.toolType ?? "工具运行";
    const status = card.status ?? (message.isLoading ? "running" : "completed");
    const running = String(status) === "running";
    return (
      <article className={`${classes} card-message`}>
        <div className="message-content">
          <details className={`tool-message status-${statusClass(status)}`} open={running}>
            <summary>
              <span className="tool-icon"><Icon name={toolIcon(card)} size={16} /></span>
              <span className="tool-heading">
                <strong className={running ? "shimmer" : ""}>{String(title)}</strong>
              </span>
              <span className="tool-status-toggle">
                <span className="tool-status">{running ? toolTypeLabel(card) : statusLabel(status)}</span>
                <Icon className="tool-chevron" name="chevron-down" size={14} />
              </span>
            </summary>
            <div className="tool-detail">
              <pre>{JSON.stringify(card, null, 2)}</pre>
            </div>
          </details>
        </div>
      </article>
    );
  }

  const text = String(content.text ?? "");
  const reasoningStreaming = active || Boolean(message.isLoading);
  return (
    <article className={classes}>
      <div className="message-content">
        {reasoning && (
          <details className={`message-reasoning${reasoningStreaming ? " streaming" : ""}`} open={reasoningStreaming}>
            <summary>
              <span className="reasoning-toggle-label">
                <span className="reasoning-label">{reasoningStreaming ? "正在思考" : "思考过程"}</span>
                <Icon className="reasoning-chevron" name="chevron-down" size={16} />
              </span>
            </summary>
            <div className="reasoning-body">
              <div className="message-text" dangerouslySetInnerHTML={{ __html: markdownToHtml(reasoning) }} />
            </div>
          </details>
        )}
        {message.isLoading && !text ? (
          <span className="thinking-dots" role="status" aria-label="正在思考">
            <span /><span /><span />
          </span>
        ) : (
          text && (
            <div
              className="message-text"
              dangerouslySetInnerHTML={{ __html: markdownToHtml(text) }}
            />
          )
        )}
        <MessageAttachments attachments={attachments} />
      </div>
    </article>
  );
}

export function ChatPanel({
  conversation,
  messages,
  approvals,
  globalError,
  sending,
  activeTaskId,
  onOpenConversations,
  onDelete,
  onSend,
  onCancel,
  onResolveApproval,
  onClearError,
  onAttachmentError,
  quickPhrases,
}: ChatPanelProps) {
  const [draft, setDraft] = useState("");
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const messageListRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const attachmentInputRef = useRef<HTMLInputElement>(null);
  const sortedMessages = [...messages].sort((left, right) => messageTime(left) - messageTime(right));
  const activeTailMessage = activeTaskId
    ? [...sortedMessages].reverse().find((message) => Number(message.user) !== 1) ?? null
    : null;
  const canManageConversation = String(conversation?.id ?? "").trim().length > 0;
  const isProcessing = sending || Boolean(activeTaskId);
  const canSend = !isProcessing && Boolean(draft.trim() || attachments.length);

  useEffect(() => {
    const list = messageListRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [messages, activeTaskId]);

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 96)}px`;
  }, [draft]);

  async function submit(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (!canSend) return;
    onClearError();
    const sent = await onSend(draft.trim(), attachments);
    if (sent) {
      setDraft("");
      setAttachments([]);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
  }

  // 与 Android 端 applyQuickPhrase 一致：点击短语直接替换输入框内容
  function applyPhrase(phrase: QuickPhrase) {
    setDraft(phrase.content);
    textareaRef.current?.focus();
  }

  async function addAttachments(event: ChangeEvent<HTMLInputElement>) {
    const files = [...(event.target.files ?? [])];
    event.target.value = "";
    if (!files.length) return;
    try {
      const nextAttachments = await Promise.all(files.map(fileToAttachment));
      setAttachments((current) => [...current, ...nextAttachments]);
    } catch (error) {
      onAttachmentError(error);
    }
  }

  return (
    <section className="chat-pane">
      <header className="chat-app-bar">
        <button
          className="appbar-icon menu-trigger"
          type="button"
          aria-label="打开对话列表"
          onClick={onOpenConversations}
        >
          <Icon name="menu" size={20} />
        </button>
        <div className="chat-header-actions">
          <button
            className="appbar-icon danger"
            type="button"
            aria-label="删除对话"
            title="删除对话"
            disabled={!canManageConversation}
            onClick={onDelete}
          >
            <Icon name="trash" size={18} />
          </button>
        </div>
      </header>

      {globalError && <div className="global-error" role="alert">{globalError}</div>}

      <div className="message-list" aria-live="polite" ref={messageListRef}>
        {!sortedMessages.length && <EmptyGreeting />}
        {sortedMessages.map((message, index) => (
          <Message
            message={message}
            active={message === activeTailMessage}
            key={String(message.id ?? `${messageTime(message)}-${index}`)}
          />
        ))}
        {approvals.map((approval) => (
          <article className={`approval-card risk-${approval.riskLevel}`} key={approval.id}>
            <header>
              <Icon name="shield" size={18} />
              <strong>需要审批</strong>
              <span>{approval.riskLevel.toUpperCase()}</span>
            </header>
            <p className="approval-summary">{approval.summary || approval.toolName}</p>
            <p>{approval.reason}</p>
            {approval.workspace && <small>工作区：{approval.workspace}</small>}
            <details>
              <summary>查看工具参数</summary>
              <pre>{approval.argumentsJson}</pre>
            </details>
            <div className="approval-actions">
              <button type="button" onClick={() => onResolveApproval(approval.id, false)}>拒绝</button>
              <button className="approve" type="button" onClick={() => onResolveApproval(approval.id, true)}>批准并继续</button>
            </div>
          </article>
        ))}
      </div>

      <div className="composer-region">
        <form className="composer" onSubmit={(event) => void submit(event)}>
          {!!attachments.length && (
            <div className="attachment-list">
              {attachments.map((attachment, index) => (
                <div className={`composer-attachment${attachment.isImage ? " image" : ""}`} key={`${attachment.fileName}-${index}`}>
                  {attachment.isImage ? (
                    <img src={attachment.dataUrl} alt={attachment.fileName} />
                  ) : (
                    <>
                      <Icon name="file" size={16} />
                      <span>
                        <strong>{attachment.fileName}</strong>
                        <small>{formatBytes(attachment.size)}</small>
                      </span>
                    </>
                  )}
                  <button
                    type="button"
                    aria-label={`移除 ${attachment.fileName}`}
                    onClick={() => setAttachments((current) => current.filter((_, itemIndex) => itemIndex !== index))}
                  >
                    <Icon name="x" size={12} />
                  </button>
                </div>
              ))}
            </div>
          )}
          {Boolean(quickPhrases.length) && (
            <div className="phrase-bar" role="toolbar" aria-label="快捷短语">
              {quickPhrases.map((phrase) => (
                <button
                  className="phrase-chip"
                  type="button"
                  key={phrase.id}
                  title={phrase.description || phrase.content}
                  onClick={() => applyPhrase(phrase)}
                >
                  {phrase.title}
                </button>
              ))}
            </div>
          )}
          <textarea
            ref={textareaRef}
            rows={1}
            placeholder="请输入内容"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={handleKeyDown}
          />
          <div className="composer-actions">
            <button
              className="composer-icon-button"
              type="button"
              aria-label="添加附件"
              title="添加附件"
              onClick={() => attachmentInputRef.current?.click()}
            >
              <ComposerAttachmentIcon />
            </button>
            <input ref={attachmentInputRef} type="file" multiple hidden onChange={(event) => void addAttachments(event)} />
            <span className="composer-hint">Enter 发送 · Shift + Enter 换行</span>
            {activeTaskId ? (
              <button className="send-button stop" type="button" aria-label="停止" title="停止" onClick={onCancel}>
                <ComposerStopIcon />
              </button>
            ) : (
              <button className={`send-button${sending ? " loading" : ""}`} type="submit" aria-label="发送" disabled={!canSend}>
                <ComposerSendIcon />
              </button>
            )}
          </div>
        </form>
      </div>
    </section>
  );
}
