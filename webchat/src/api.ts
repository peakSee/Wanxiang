const API_ROOT = "/webchat/api";
let authToken = "";

export function setAuthToken(token: string): void {
  authToken = token.trim();
}

interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  body?: unknown;
  query?: Record<string, string | number | boolean | null | undefined>;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, query } = options;
  const url = new URL(`${API_ROOT}${path}`, window.location.origin);
  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    });
  }

  const response = await fetch(url, {
    method,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const responseText = await response.text();
  let payload: unknown = null;
  if (responseText) {
    try {
      payload = JSON.parse(responseText);
    } catch {
      payload = responseText;
    }
  }
  if (!response.ok) {
    const record = isRecord(payload) ? payload : null;
    const message = record?.error ?? record?.message ?? payload;
    throw new Error(String(message || `请求失败 (${response.status})`));
  }
  return payload as T;
}

export function workspaceDownloadUrl(path: string): string {
  return `${API_ROOT}/workspaces/download?path=${encodeURIComponent(path)}&token=${encodeURIComponent(authToken)}`;
}

export function eventsUrl(): string {
  return `${API_ROOT}/events?token=${encodeURIComponent(authToken)}`;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
