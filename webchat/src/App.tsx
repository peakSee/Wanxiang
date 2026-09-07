import { useEffect, useRef, useState } from "react";
import { request, setAuthToken } from "./api";
import { ChatPanel } from "./components/ChatPanel";
import { ContextPane } from "./components/ContextPane";
import { ConversationSidebar } from "./components/ConversationSidebar";
import { Icon } from "./components/Icon";
import { LoginView } from "./components/LoginView";
import {
  createConversationDraft,
  isPersistedConversation,
} from "./conversationDraft";
import { conversationKey } from "./format";
import { useRealtime } from "./hooks/useRealtime";
import { normalizeQuickPhrases } from "./quickPhrases";
import type {
  ApprovalRequest,
  ApprovalResult,
  Attachment,
  BootstrapPayload,
  ChatMessage,
  Conversation,
  ConversationCreateTarget,
  ConversationMode,
  MobileSection,
  QuickPhrase,
  RealtimeEventData,
  RealtimeEventName,
  RunResult,
  WorkspaceFilePayload,
  WorkspaceInfo,
  WorkspaceItem,
  WorkspaceListing,
} from "./types";

const TOKEN_STORAGE_KEY = "wanxiang_webchat_token";
const MOBILE_SECTION_ICON = {
  chat: "agent",
  workspace: "workspace",
} as const;
const CONVERSATION_MODES = new Set<ConversationMode>(["normal"]);

function normalizeConversationMode(mode: string | undefined): ConversationMode {
  return CONVERSATION_MODES.has(mode as ConversationMode)
    ? (mode as ConversationMode)
    : "normal";
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error ?? "请求失败");
}

function initialToken(): string {
  const queryToken = new URLSearchParams(window.location.search).get("token")?.trim();
  return queryToken || localStorage.getItem(TOKEN_STORAGE_KEY)?.trim() || "";
}

function createTaskId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

export default function App() {
  const [authenticated, setAuthenticated] = useState(false);
  const [authenticating, setAuthenticating] = useState(false);
  const [loginError, setLoginError] = useState("");
  const [globalError, setGlobalError] = useState("");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedConversation, setSelectedConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [approvals, setApprovals] = useState<ApprovalRequest[]>([]);
  const [sending, setSending] = useState(false);
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const [activeRenderTaskId, setActiveRenderTaskId] = useState<string | null>(null);
  const [workspaceInfo, setWorkspaceInfo] = useState<WorkspaceInfo | null>(null);
  const [workspacePath, setWorkspacePath] = useState("");
  const [workspaceItems, setWorkspaceItems] = useState<WorkspaceItem[]>([]);
  const [workspaceFilePath, setWorkspaceFilePath] = useState<string | null>(null);
  const [workspaceContent, setWorkspaceContent] = useState("");
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [quickPhrases, setQuickPhrases] = useState<QuickPhrase[]>([]);
  const [mobileSection, setMobileSectionState] = useState<MobileSection>("chat");
  const [conversationsOpen, setConversationsOpen] = useState(false);
  const [leftSidebarCollapsed, setLeftSidebarCollapsed] = useState(false);
  const [rightSidebarCollapsed, setRightSidebarCollapsed] = useState(false);
  const [toast, setToast] = useState("");
  const selectedRef = useRef<Conversation | null>(null);
  const conversationHistoryRef = useRef<string[]>([]);
  const conversationHistoryIndexRef = useRef(-1);
  const completedTaskIdsRef = useRef(new Set<string>());
  const workspacePathRef = useRef("");
  const toastTimerRef = useRef<number | null>(null);
  const autoLoginToken = useRef(initialToken());

  function showError(error: unknown) {
    setGlobalError(errorMessage(error));
  }

  function showToast(message: string) {
    if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    setToast(message);
    toastTimerRef.current = window.setTimeout(() => setToast(""), 2600);
  }

  function applyConversationSnapshot(conversation: Conversation) {
    if (!isPersistedConversation(conversation)) return;
    setConversations((current) => [
      conversation,
      ...current.filter((item) => String(item.id) !== String(conversation.id)),
    ].sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0)));
    if (String(selectedRef.current?.id ?? "") === String(conversation.id)) {
      selectedRef.current = conversation;
      setSelectedConversation(conversation);
    }
  }

  function recordConversationNavigation(conversation: Conversation | null) {
    if (!isPersistedConversation(conversation)) return;
    const key = conversationKey(conversation);
    const history = conversationHistoryRef.current;
    const index = conversationHistoryIndexRef.current;
    if (history[index] === key) return;
    const nextHistory = [...history.slice(0, index + 1), key].slice(-50);
    conversationHistoryRef.current = nextHistory;
    conversationHistoryIndexRef.current = nextHistory.length - 1;
  }

  function navigationTarget(direction: -1 | 1) {
    const conversationsByKey = new Map(
      conversations.map((conversation) => [conversationKey(conversation), conversation]),
    );
    const history = conversationHistoryRef.current;
    for (
      let index = conversationHistoryIndexRef.current + direction;
      index >= 0 && index < history.length;
      index += direction
    ) {
      const conversation = conversationsByKey.get(history[index]);
      if (conversation) return { conversation, index };
    }
    return null;
  }

  async function navigateConversationHistory(direction: -1 | 1) {
    const target = navigationTarget(direction);
    if (!target) return;
    conversationHistoryIndexRef.current = target.index;
    selectedRef.current = target.conversation;
    setSelectedConversation(target.conversation);
    setApprovals([]);
    await Promise.all([
      loadMessages(target.conversation),
      loadApprovals(target.conversation),
    ]);
  }

  async function loadMessages(conversation = selectedRef.current) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    try {
      const payload = await request<ChatMessage[]>(`/conversations/${conversation.id}/messages`, {
        query: { mode: conversation.mode ?? "normal" },
      });
      if (conversationKey(conversation) === conversationKey(selectedRef.current)) {
        const incoming = Array.isArray(payload) ? payload : [];
        setMessages(incoming);
      }
    } catch (error) {
      showError(error);
    }
  }

  async function loadApprovals(conversation = selectedRef.current) {
    if (!conversation || !isPersistedConversation(conversation)) {
      setApprovals([]);
      return;
    }
    try {
      const payload = await request<ApprovalRequest[]>(`/conversations/${conversation.id}/approvals`);
      if (conversationKey(conversation) === conversationKey(selectedRef.current)) {
        setApprovals(Array.isArray(payload) ? payload : []);
      }
    } catch {
      setApprovals([]);
    }
  }

  async function resolveApproval(requestId: string, approved: boolean) {
    const conversation = selectedRef.current;
    if (!conversation || !isPersistedConversation(conversation)) return;
    try {
      const result = await request<ApprovalResult>(
        `/conversations/${conversation.id}/approvals/${encodeURIComponent(requestId)}`,
        {
          method: "POST",
          body: { approved },
        },
      );
      setApprovals((current) => current.filter((item) => item.id !== requestId));
      if (result?.taskId) {
        setActiveTaskId(result.taskId);
        setActiveRenderTaskId(result.taskId);
      }
      showToast(approved ? "已批准并继续执行" : "已拒绝执行");
      await loadMessages(conversation);
    } catch (error) {
      showError(error);
    }
  }

  async function loadConversations(preserveSelection = true) {
    const payload = await request<Conversation[]>("/conversations");
    const previousKey = preserveSelection ? conversationKey(selectedRef.current) : null;
    const nextConversations = (Array.isArray(payload) ? payload : [])
      .sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0));
    const currentSelection = selectedRef.current;
    if (
      preserveSelection
      && currentSelection
      && !isPersistedConversation(currentSelection)
    ) {
      setConversations(nextConversations);
      setSelectedConversation(currentSelection);
      return;
    }
    const nextSelected = nextConversations.find((item) => conversationKey(item) === previousKey)
      ?? nextConversations[0]
      ?? null;
    const selectionChanged = conversationKey(nextSelected) !== previousKey;
    setConversations(nextConversations);
    selectedRef.current = nextSelected;
    setSelectedConversation(nextSelected);
    recordConversationNavigation(nextSelected);
    if (nextSelected) {
      if (selectionChanged) {
        setMessages([]);
        setApprovals([]);
      }
      await Promise.all([
        loadMessages(nextSelected),
        loadApprovals(nextSelected),
      ]);
    } else {
      setMessages([]);
      setApprovals([]);
    }
  }

  async function loadWorkspace(path = workspacePathRef.current, reportError = true) {
    if (!path) return;
    try {
      const payload = await request<WorkspaceListing>("/workspaces", { query: { path } });
      const nextPath = String(payload?.path ?? path);
      workspacePathRef.current = nextPath;
      setWorkspacePath(nextPath);
      setWorkspaceItems(Array.isArray(payload?.items) ? payload.items : []);
    } catch (error) {
      if (reportError) showError(error);
    }
  }

  async function authenticate(token: string) {
    setLoginError("");
    setAuthenticating(true);
    try {
      await request("/session/bootstrap", { method: "POST", body: { token } });
      setAuthToken(token);
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
      const bootstrap = await request<BootstrapPayload>("/bootstrap");
      const info = bootstrap?.workspace?.workspace ?? null;
      const rootPath = bootstrap?.workspace?.root?.path ?? info?.rootPath ?? "";
      setWorkspaceInfo(info);
      setQuickPhrases(normalizeQuickPhrases(bootstrap?.quickPhrases));
      workspacePathRef.current = rootPath;
      setWorkspacePath(rootPath);
      setAuthenticated(true);

      const url = new URL(window.location.href);
      if (url.searchParams.has("token")) {
        url.searchParams.delete("token");
        window.history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
      }
      await Promise.all([
        loadConversations(false),
        rootPath ? loadWorkspace(rootPath, false) : Promise.resolve(),
      ]);
    } catch (error) {
      setAuthToken("");
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      setLoginError(errorMessage(error));
      setAuthenticated(false);
    } finally {
      setAuthenticating(false);
    }
  }

  function createConversation(target: ConversationCreateTarget) {
    setGlobalError("");
    const draftConversation = createConversationDraft(
      target.mode,
      Date.now(),
    );
    selectedRef.current = draftConversation;
    setSelectedConversation(draftConversation);
    setMessages([]);
    setApprovals([]);
    setActiveTaskId(null);
    setActiveRenderTaskId(null);
    setConversationsOpen(false);
  }

  async function selectConversation(conversation: Conversation) {
    selectedRef.current = conversation;
    setSelectedConversation(conversation);
    setMessages([]);
    setApprovals([]);
    recordConversationNavigation(conversation);
    setConversationsOpen(false);
    await Promise.all([
      loadMessages(conversation),
      loadApprovals(conversation),
    ]);
  }

  async function deleteConversation(conversation: Conversation | null = selectedRef.current) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    if (!window.confirm(`删除“${conversation.title || "当前对话"}”？此操作无法撤销。`)) return;
    try {
      await request(`/conversations/${conversation.id}`, { method: "DELETE" });
      await loadConversations(true);
      showToast("会话已删除");
    } catch (error) {
      showError(error);
    }
  }

  async function sendMessage(text: string, attachments: Attachment[]): Promise<boolean> {
    setGlobalError("");
    setSending(true);
    let conversationCreatedForSend: Conversation | null = null;
    let optimisticUserEntryId: string | null = null;
    try {
      let conversation = selectedRef.current;
      if (!isPersistedConversation(conversation)) {
        const draftMode = normalizeConversationMode(conversation?.mode);
        const currentWorkspace = workspacePathRef.current.startsWith("/workspace/")
          ? workspacePathRef.current.split("/").slice(0, 3).join("/")
          : "";
        conversation = await request<Conversation>("/conversations", {
          method: "POST",
          body: {
            title: text.trim().split(/\r?\n/, 1)[0].slice(0, 48) || "图片任务",
            mode: draftMode,
            ...(currentWorkspace ? { workspace: currentWorkspace } : {}),
          },
        });
        conversation = {
          ...conversation,
          mode: draftMode,
        };
        conversationCreatedForSend = conversation;
        selectedRef.current = conversation;
        setSelectedConversation(conversation);
        applyConversationSnapshot(conversation);
        recordConversationNavigation(conversation);
      }
      if (!conversation) throw new Error("无法创建新对话");
      const conversationMode = normalizeConversationMode(conversation.mode);
      const taskId = createTaskId();
      const userMessageCreatedAt = Date.now();
      optimisticUserEntryId = `${taskId}-user`;
      const optimisticUserMessage: ChatMessage = {
        id: optimisticUserEntryId,
        type: 1,
        user: 1,
        content: {
          id: optimisticUserEntryId,
          text,
          ...(attachments.length ? { attachments } : {}),
        },
        createAt: userMessageCreatedAt,
      };
      completedTaskIdsRef.current.delete(taskId);
      setActiveTaskId(taskId);
      setActiveRenderTaskId(taskId);
      setMessages((current) => [...current, optimisticUserMessage]);
      const result = await request<RunResult>(`/conversations/${conversation.id}/runs`, {
        method: "POST",
        body: {
          taskId,
          userMessage: text,
          userMessageCreatedAt,
          conversationMode,
          attachments,
        },
      });
      if (result?.conversation) {
        const updatedConversation = {
          ...result.conversation,
          mode: result.conversationMode ?? conversationMode,
        };
        selectedRef.current = updatedConversation;
        setSelectedConversation(updatedConversation);
        applyConversationSnapshot(updatedConversation);
      }
      const acceptedTaskId = String(result?.taskId ?? taskId);
      const completedBeforeAcceptance = completedTaskIdsRef.current.has(acceptedTaskId);
      setActiveTaskId(completedBeforeAcceptance ? null : acceptedTaskId);
      setActiveRenderTaskId(
        completedBeforeAcceptance
          ? null
          : String(result?.turnId ?? acceptedTaskId),
      );
      completedTaskIdsRef.current.delete(acceptedTaskId);
      void loadConversations(true).catch(showError);
      return true;
    } catch (error) {
      showError(error);
      setActiveTaskId(null);
      setActiveRenderTaskId(null);
      if (optimisticUserEntryId) {
        const failedEntryId = optimisticUserEntryId;
        setMessages((current) => current.filter((message) => (
          String(message.id ?? message.contentId ?? "") !== failedEntryId
        )));
      }
      if (conversationCreatedForSend) {
        const createdConversation = conversationCreatedForSend;
        try {
          const persistedMessages = await request<ChatMessage[]>(
            `/conversations/${createdConversation.id}/messages`,
            { query: { mode: createdConversation.mode ?? "normal" } },
          );
          if (!persistedMessages.length) {
            await request(`/conversations/${createdConversation.id}`, { method: "DELETE" });
            if (
              String(selectedRef.current?.id ?? "") ===
              String(createdConversation.id)
            ) {
              const draft = createConversationDraft(
                normalizeConversationMode(createdConversation.mode),
                Date.now(),
              );
              selectedRef.current = draft;
              setSelectedConversation(draft);
              setMessages([]);
              setApprovals([]);
            }
          }
        } catch {
          // Keep the conversation when its persisted state cannot be confirmed.
        }
      }
      const current = selectedRef.current;
      if (isPersistedConversation(current)) {
        void loadMessages(current);
        void loadApprovals(current);
      }
      return false;
    } finally {
      setSending(false);
    }
  }

  async function cancelRun() {
    if (!activeTaskId) return;
    try {
      await request(`/tasks/${encodeURIComponent(activeTaskId)}/cancel`, { method: "POST" });
      setActiveTaskId(null);
      setActiveRenderTaskId(null);
    } catch (error) {
      showError(error);
    }
  }

  async function openWorkspaceFile(path: string) {
    try {
      const payload = await request<WorkspaceFilePayload>("/workspaces/file", {
        query: { path, maxChars: 64_000 },
      });
      setWorkspaceFilePath(path);
      setWorkspaceContent(String(payload?.content ?? ""));
      setWorkspaceDirty(false);
    } catch (error) {
      showError(error);
    }
  }

  function workspaceParentPath(): string {
    const root = String(workspaceInfo?.rootPath ?? "").replace(/\/$/, "");
    const current = String(workspacePathRef.current).replace(/\/$/, "");
    if (!current || current === root) return current;
    const index = current.lastIndexOf("/");
    const parent = index > 0 ? current.slice(0, index) : "/";
    return root && !parent.startsWith(root) ? root : parent;
  }

  async function saveWorkspaceFile() {
    if (!workspaceFilePath || !workspaceDirty) return;
    try {
      await request("/workspaces/file", {
        method: "PUT",
        body: { path: workspaceFilePath, content: workspaceContent, append: false },
      });
      setWorkspaceDirty(false);
      showToast("文件已保存");
    } catch (error) {
      showError(error);
    }
  }

  function sameSelectedConversation(data: RealtimeEventData): boolean {
    const selected = selectedRef.current;
    return Boolean(
      selected
      && String(data.conversationId ?? "") === String(selected.id)
      && String(data.conversationMode ?? data.mode ?? "normal") === String(selected.mode ?? "normal"),
    );
  }

  function handleRealtimeEvent(eventName: RealtimeEventName, data: RealtimeEventData) {
    if (["conversation_created", "conversation_updated", "conversation_deleted"].includes(eventName)) {
      void loadConversations(true).catch(showError);
      return;
    }
    if (eventName === "messages_replaced" && sameSelectedConversation(data)) {
      const incoming = Array.isArray(data.messages) ? data.messages : [];
      setMessages(incoming);
      return;
    }
    if (eventName === "workspace_changed" && workspacePathRef.current) {
      void loadWorkspace(workspacePathRef.current, false);
      return;
    }
    if (eventName === "chat_task_event") {
      const kind = String(data.kind ?? "");
      const taskId = String(data.taskId ?? "");
      if (["completed", "error"].includes(kind)) {
        if (taskId) completedTaskIdsRef.current.add(taskId);
        setActiveTaskId(null);
        setActiveRenderTaskId(null);
        setApprovals([]);
      } else if (kind === "waiting_approval") {
        setActiveTaskId(null);
        setActiveRenderTaskId(null);
        if (Array.isArray(data.approvals)) {
          setApprovals(data.approvals as ApprovalRequest[]);
        } else if (selectedRef.current && isPersistedConversation(selectedRef.current)) {
          void loadApprovals(selectedRef.current);
        }
      }
      return;
    }
  }

  const connectionStatus = useRealtime(authenticated, handleRealtimeEvent);

  function selectMobileSection(section: MobileSection) {
    setMobileSectionState(section);
  }

  useEffect(() => {
    const token = autoLoginToken.current;
    if (token) void authenticate(token);
    return () => {
      if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    };
  }, []);

  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (!workspaceDirty) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [workspaceDirty]);

  if (!authenticated) {
    return (
      <LoginView
        initialToken={autoLoginToken.current}
        busy={authenticating}
        error={loginError}
        onLogin={authenticate}
      />
    );
  }

  const previousConversation = navigationTarget(-1);
  const nextConversation = navigationTarget(1);

  return (
    <>
      <div
        className={[
          "app-view",
          conversationsOpen && "conversations-open",
          leftSidebarCollapsed && "left-sidebar-collapsed",
          rightSidebarCollapsed && "right-sidebar-collapsed",
        ].filter(Boolean).join(" ")}
        data-mobile-section={mobileSection}
      >
        <header className="desktop-navigation-bar">
          <nav className="desktop-navigation-group" aria-label="对话导航">
            <button
              className="topbar-icon"
              type="button"
              aria-label={leftSidebarCollapsed ? "展开左侧边栏" : "收起左侧边栏"}
              title={leftSidebarCollapsed ? "展开左侧边栏" : "收起左侧边栏"}
              aria-pressed={!leftSidebarCollapsed}
              onClick={() => setLeftSidebarCollapsed((collapsed) => !collapsed)}
            >
              <Icon name="panel-left" size={18} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label="回退到上一会话"
              title="回退到上一会话"
              disabled={!previousConversation}
              onClick={() => void navigateConversationHistory(-1)}
            >
              <Icon name="arrow-left" size={18} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label="前进到下一会话"
              title="前进到下一会话"
              disabled={!nextConversation}
              onClick={() => void navigateConversationHistory(1)}
            >
              <Icon name="arrow-right" size={18} />
            </button>
          </nav>
          <div className="desktop-navigation-group desktop-navigation-end">
            <button
              className="topbar-icon danger"
              type="button"
              aria-label="删除对话"
              title="删除对话"
              disabled={!isPersistedConversation(selectedConversation)}
              onClick={() => void deleteConversation()}
            >
              <Icon name="trash" size={16} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label={rightSidebarCollapsed ? "展开右侧边栏" : "收起右侧边栏"}
              title={rightSidebarCollapsed ? "展开右侧边栏" : "收起右侧边栏"}
              aria-pressed={!rightSidebarCollapsed}
              onClick={() => setRightSidebarCollapsed((collapsed) => !collapsed)}
            >
              <Icon name="panel-right" size={18} />
            </button>
          </div>
        </header>
        <ConversationSidebar
          conversations={conversations}
          selected={selectedConversation}
          connectionStatus={connectionStatus}
          onCreate={createConversation}
          onSelect={(conversation) => void selectConversation(conversation)}
          onDelete={(conversation) => deleteConversation(conversation)}
        />
        <ChatPanel
          conversation={selectedConversation}
          messages={messages}
          approvals={approvals}
          globalError={globalError}
          sending={sending}
          activeTaskId={activeRenderTaskId ?? activeTaskId}
          onOpenConversations={() => setConversationsOpen(true)}
          onDelete={() => void deleteConversation()}
          onSend={sendMessage}
          onCancel={() => void cancelRun()}
          onResolveApproval={resolveApproval}
          onClearError={() => setGlobalError("")}
          onAttachmentError={showError}
          quickPhrases={quickPhrases}
        />
        <ContextPane
          workspacePath={workspacePath}
          workspaceItems={workspaceItems}
          workspaceFilePath={workspaceFilePath}
          workspaceContent={workspaceContent}
          workspaceDirty={workspaceDirty}
          onOpenConversations={() => setConversationsOpen(true)}
          onWorkspacePath={() => {
            const parent = workspaceParentPath();
            if (parent && parent !== workspacePathRef.current) void loadWorkspace(parent);
          }}
          onWorkspaceItem={(item) => {
            if (item.isDirectory) void loadWorkspace(item.path);
            else void openWorkspaceFile(item.path);
          }}
          onWorkspaceRefresh={() => void loadWorkspace()}
          onWorkspaceContent={(content) => {
            setWorkspaceContent(content);
            setWorkspaceDirty(true);
          }}
          onWorkspaceSave={() => void saveWorkspaceFile()}
        />

        <nav className="mobile-nav" aria-label="Web Chat 区域">
          {(["chat", "workspace"] as MobileSection[]).map((section) => (
            <button
              className={mobileSection === section ? "active" : ""}
              type="button"
              onClick={() => selectMobileSection(section)}
              key={section}
            >
              <Icon name={MOBILE_SECTION_ICON[section]} size={18} />
              <span>{{ chat: "智枢", workspace: "工作区" }[section]}</span>
            </button>
          ))}
        </nav>
        <button
          className="conversation-scrim"
          type="button"
          aria-label="关闭对话列表"
          onClick={() => setConversationsOpen(false)}
        />
      </div>
      {toast && <div className="toast" role="status">{toast}</div>}
    </>
  );
}
