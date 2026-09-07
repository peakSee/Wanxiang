import { useState, type FormEvent } from "react";

interface LoginViewProps {
  initialToken: string;
  busy: boolean;
  error: string;
  onLogin: (token: string) => Promise<void>;
}

export function LoginView({ initialToken, busy, error, onLogin }: LoginViewProps) {
  const [token, setToken] = useState(initialToken);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = token.trim();
    if (value) void onLogin(value);
  }

  return (
    <main className="login-view">
      <section className="login-panel" aria-label="连接万象智枢">
        <form className="login-form" onSubmit={submit}>
          <label htmlFor="token-input">万象智枢配对码</label>
          <input
            id="token-input"
            name="token"
            type="password"
            autoComplete="current-password"
            value={token}
            disabled={busy}
            onChange={(event) => setToken(event.target.value)}
            required
          />
          <button className="primary-button" type="submit" disabled={busy}>
            {busy ? "正在连接…" : "进入协作台"}
          </button>
        </form>
        <p className="form-error" role="alert">{error}</p>
      </section>
    </main>
  );
}
