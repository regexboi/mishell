UI_HTML = """<!doctype html>
<html lang=\"en\">
<head>
  <meta charset=\"utf-8\" />
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
  <title>Mishell MCP Config</title>
  <style>
    :root {
      --bg1: #1a1032;
      --bg2: #3f1f6c;
      --glass: rgba(255, 255, 255, 0.12);
      --glass-border: rgba(255, 255, 255, 0.22);
      --txt: #f5f2ff;
      --muted: #cfc6ef;
      --ok: #79f2b1;
      --err: #ff9fb3;
      --shadow: rgba(8, 4, 18, 0.45);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      font-family: \"Manrope\", \"Segoe UI\", sans-serif;
      color: var(--txt);
      background:
        radial-gradient(1200px 700px at -10% -20%, #6f41bb55, transparent 55%),
        radial-gradient(1000px 700px at 110% 10%, #b37bff44, transparent 50%),
        linear-gradient(130deg, var(--bg1), var(--bg2));
      display: grid;
      place-items: center;
      padding: 20px;
    }
    .card {
      width: min(980px, 100%);
      border-radius: 22px;
      border: 1px solid var(--glass-border);
      background: var(--glass);
      backdrop-filter: blur(14px) saturate(135%);
      box-shadow: 0 22px 45px var(--shadow);
      overflow: hidden;
      animation: lift .45s ease;
    }
    @keyframes lift {
      from { opacity: 0; transform: translateY(10px) scale(.99); }
      to { opacity: 1; transform: translateY(0) scale(1); }
    }
    .head {
      padding: 18px 20px;
      border-bottom: 1px solid var(--glass-border);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 { margin: 0; font-size: 1.08rem; letter-spacing: .02em; }
    .meta { color: var(--muted); font-size: .9rem; }
    .body { padding: 16px; display: grid; gap: 12px; }
    textarea {
      width: 100%;
      min-height: 420px;
      resize: vertical;
      border-radius: 14px;
      border: 1px solid var(--glass-border);
      background: rgba(13, 7, 28, 0.56);
      color: var(--txt);
      font-family: \"IBM Plex Mono\", ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: .86rem;
      line-height: 1.4;
      padding: 14px;
      outline: none;
    }
    .auth-box {
      display: grid;
      gap: 10px;
      max-width: 420px;
      width: 100%;
    }
    .auth-row {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }
    input[type=\"password\"] {
      flex: 1;
      min-width: 220px;
      border-radius: 999px;
      border: 1px solid var(--glass-border);
      background: rgba(13, 7, 28, 0.56);
      color: var(--txt);
      padding: 10px 14px;
      outline: none;
    }
    .actions { display: flex; gap: 10px; flex-wrap: wrap; }
    button {
      border: 1px solid var(--glass-border);
      border-radius: 999px;
      padding: 9px 14px;
      color: var(--txt);
      background: linear-gradient(180deg, #b787ff45, #8f59df42);
      cursor: pointer;
      font-weight: 700;
      letter-spacing: .01em;
    }
    button:hover { transform: translateY(-1px); }
    .status {
      border-radius: 12px;
      border: 1px solid var(--glass-border);
      background: rgba(11, 7, 25, .44);
      padding: 10px 12px;
      font-size: .9rem;
      color: var(--muted);
      white-space: pre-wrap;
      min-height: 50px;
    }
    .hidden { display: none !important; }
    .ok { color: var(--ok); }
    .err { color: var(--err); }
  </style>
</head>
<body>
  <main class=\"card\">
    <div class=\"head\">
      <h1>Mishell MCP Config</h1>
      <div class=\"meta\" id=\"meta\">Checking auth…</div>
    </div>

    <div class=\"body\" id=\"authGate\">
      <div class=\"auth-box\">
        <div>Enter API key to unlock config + MCP HTTP endpoints.</div>
        <div class=\"auth-row\">
          <input id=\"apiKey\" type=\"password\" placeholder=\"MISHELL API key\" autocomplete=\"off\" />
          <button id=\"login\">Unlock</button>
        </div>
      </div>
      <div class=\"status\" id=\"authStatus\">Waiting for key.</div>
    </div>

    <div class=\"body hidden\" id=\"appBody\">
      <textarea id=\"toml\" spellcheck=\"false\"></textarea>
      <div class=\"actions\">
        <button id=\"refresh\">Refresh</button>
        <button id=\"save\">Save</button>
        <button id=\"reload\">Reload</button>
        <button id=\"logout\">Lock</button>
      </div>
      <div class=\"status\" id=\"status\">Ready.</div>
    </div>
  </main>

  <script>
    const $ = (id) => document.getElementById(id);
    const metaEl = $("meta");

    const authGateEl = $("authGate");
    const authStatusEl = $("authStatus");
    const apiKeyEl = $("apiKey");

    const appBodyEl = $("appBody");
    const statusEl = $("status");
    const tomlEl = $("toml");

    function setStatus(text, kind) {
      statusEl.textContent = text;
      statusEl.classList.remove("ok", "err");
      if (kind) statusEl.classList.add(kind);
    }

    function setAuthStatus(text, kind) {
      authStatusEl.textContent = text;
      authStatusEl.classList.remove("ok", "err");
      if (kind) authStatusEl.classList.add(kind);
    }

    function lockUI(message) {
      authGateEl.classList.remove("hidden");
      appBodyEl.classList.add("hidden");
      metaEl.textContent = "locked";
      setAuthStatus(message || "Enter API key.", "err");
    }

    function unlockUI(source) {
      authGateEl.classList.add("hidden");
      appBodyEl.classList.remove("hidden");
      const sourceText = source ? ` (${source})` : "";
      metaEl.textContent = `authenticated${sourceText}`;
    }

    async function getJson(path, init) {
      const res = await fetch(path, {
        credentials: "same-origin",
        ...init,
      });

      let data = { ok: false, error: `HTTP ${res.status}` };
      try {
        data = await res.json();
      } catch (_) {
        // Keep fallback parse result.
      }

      return { res, data };
    }

    async function refreshConfig() {
      const { res, data } = await getJson("/api/config");
      if (res.status === 401) {
        lockUI("Session expired. Enter API key again.");
        return;
      }
      if (!data.ok) {
        setStatus(data.error || "Failed to load config", "err");
        return;
      }

      tomlEl.value = data.toml;
      metaEl.textContent = `policy_hash=${data.policy_hash} source=${data.source}`;
      const warning = data.warning ? `\nwarning=${data.warning}` : "";
      setStatus(`Config loaded.${warning}`, data.warning ? "err" : "ok");
    }

    async function saveConfig() {
      const { res, data } = await getJson("/api/config", {
        method: "PUT",
        headers: { "Content-Type": "text/plain" },
        body: tomlEl.value,
      });

      if (res.status === 401) {
        lockUI("Session expired. Enter API key again.");
        return;
      }
      if (!data.ok) {
        setStatus(data.error || "Save failed", "err");
        return;
      }

      setStatus("Saved config file. Click Reload to apply.", "ok");
    }

    async function reloadConfig() {
      const { res, data } = await getJson("/api/reload", { method: "POST" });
      if (res.status === 401) {
        lockUI("Session expired. Enter API key again.");
        return;
      }
      if (!data.ok) {
        setStatus(data.error || "Reload failed", "err");
        return;
      }

      metaEl.textContent = `policy_hash=${data.policy_hash} source=${data.source}`;
      setStatus(`Reloaded. ${data.summary || ""}`.trim(), "ok");
      if (data.toml) tomlEl.value = data.toml;
    }

    async function login() {
      const apiKey = apiKeyEl.value.trim();
      if (!apiKey) {
        setAuthStatus("API key is required.", "err");
        return;
      }

      const { data } = await getJson("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ api_key: apiKey }),
      });

      if (!data.ok) {
        setAuthStatus(data.error || "Invalid API key.", "err");
        return;
      }

      apiKeyEl.value = "";
      unlockUI("session-cookie");
      setStatus("Authenticated.", "ok");
      await refreshConfig();
    }

    async function logout() {
      await getJson("/api/auth/logout", { method: "POST" });
      lockUI("Locked. Enter API key to continue.");
    }

    async function initAuth() {
      const { data } = await getJson("/api/auth/status");
      if (!data.ok) {
        lockUI(data.error || "Auth check failed.");
        return;
      }

      if (data.enabled === false || data.authenticated) {
        unlockUI(data.source || null);
        await refreshConfig();
        return;
      }

      lockUI("Enter API key to unlock admin UI.");
    }

    $("login").addEventListener("click", login);
    $("logout").addEventListener("click", logout);
    $("refresh").addEventListener("click", refreshConfig);
    $("save").addEventListener("click", saveConfig);
    $("reload").addEventListener("click", reloadConfig);

    apiKeyEl.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        login();
      }
    });

    initAuth();
  </script>
</body>
</html>
"""
