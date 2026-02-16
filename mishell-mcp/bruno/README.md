# Bruno Collection

This folder contains a Bruno collection for Mishell HTTP endpoints.

## Setup

1. Start Mishell HTTP server on `http://127.0.0.1:8067`.
2. Open `bruno/` in Bruno Desktop, then select environment `local`.
3. Update `bruno/environments/local.bru`:
   - `apiKey` should match your `MISHELL_API_KEY`.
   - `baseUrl` should match your running server.

## Run

- Run individual requests in Bruno UI.
- Or run the full collection with CLI from repo root:

```bash
bru run /Users/mishca/scripts/mishell/mishell-mcp/bruno --env local
```

## Notes

- The `llm/01-stream-validation-error` request validates SSE error contract without calling the model.
- The `llm/02-stream-live` request hits the real stream route and requires server-side OpenAI setup (`OPENAI_API_KEY`).
- The `speech/01-transcribe-empty-body` request is a deterministic contract check and does not require uploading audio.
