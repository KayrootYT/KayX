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
      -> AiToolRegistry (strict app-code allow-list, typed validation, permission + confirmation gates)
      -> OpenAiCompatibleChatClient transport (bounded function-call schema + response parsing)
        -> HTTPS provider endpoint
      -> AiCredentialVault (Android Keystore AES-GCM)
```

## Current foundation

- Provider-neutral `AiProviderConfig` validates HTTPS endpoints and model identifiers.
- `AiCredentialVault` encrypts API credentials with a non-exportable Android Keystore AES-256-GCM key.
- `AiChatClient` separates feature logic from transport implementation.
- `OpenAiCompatibleChatClient` sends bounded, non-streaming Chat Completions requests over HTTPS and does not log prompts, response bodies, or credentials.
- Transport errors are deliberately sanitized before reaching the UI.

## Implemented in the current branch

- Function calling is now available through `AiToolChatClient`; only app-registered `AiToolSpec` definitions are sent to the provider.
- `AiToolRegistry` rejects unknown tools/arguments and wrong types, checks declared permissions, enforces timeouts/cancellation, and requires explicit confirmation for destructive tools.
- Audit events contain tool names/outcomes only; arguments, prompts, credentials and results are not passed to the audit interface.
- No file-operation tools are registered by default. The registry is infrastructure, not permission to access user files.
- File selection/consent UX and RAG ingestion.
- Local vector index and semantic search.
- Agent loop, MCP, persistent AI memory and workflow orchestration.
- Physical-device validation and provider contract tests.

These are separate acceptance-gated phases. Do not claim them from the existence of this foundation.
