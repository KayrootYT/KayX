# KayX AI architecture

## Product boundary

KayX remains an offline-first Android file manager. AI is an optional, user-configured capability:
- Local browsing, search, copy/move, archive tools and other existing features never require an AI account or network.
- No default provider, API key, telemetry, analytics, subscription or remote feature lock is introduced.
- AI requests are only made after an explicit user action.
- File contents must not be uploaded automatically. Future RAG/document features must show the selected files and request consent before sending their content.
- Destructive file operations must require a separate user confirmation; an AI response is never authorization.

## Layering

```
KayX UI
  -> AI feature/use cases (later phase)
    -> AiChatClient interface
      -> OpenAiCompatibleChatClient transport
        -> HTTPS provider endpoint
      -> AiCredentialVault (Android Keystore AES-GCM)
```

## Current foundation

- Provider-neutral `AiProviderConfig` validates HTTPS endpoints and model identifiers.
- `AiCredentialVault` encrypts API credentials with a non-exportable Android Keystore AES-256-GCM key.
- `AiChatClient` separates feature logic from transport implementation.
- `OpenAiCompatibleChatClient` sends bounded, non-streaming Chat Completions requests over HTTPS and does not log prompts, response bodies, or credentials.
- Transport errors are deliberately sanitized before reaching the UI.

## Not implemented yet

- Settings UI for provider/model/key management.
- Tool registry and function calling.
- File selection/consent UX and RAG ingestion.
- Local vector index and semantic search.
- Agent loop, MCP, persistent AI memory and workflow orchestration.
- Physical-device validation and provider contract tests.

These are separate acceptance-gated phases. Do not claim them from the existence of this foundation.
