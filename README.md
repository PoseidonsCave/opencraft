# OpenCraft

OpenCraft adds a chat-based AI assistant to ZenithProxy. Approved players can ask questions in-game with a simple prefix such as `!oc`, and admins can optionally let the assistant trigger a tightly controlled set of ZenithProxy commands on their behalf.

The plugin is designed to be useful without being reckless: player access is allowlisted, admin actions are deny-by-default, high-risk requests require confirmation, and secrets stay out of config files and chat output.

---

## Features

- In-game Q&A through whispers and, if enabled, public chat
- Member/admin access control based on UUIDs
- Deny-by-default admin command allowlist
- Per-command typed argument validation before any proxy command is dispatched
- Confirmation prompts for high-risk admin actions
- Per-user, global, concurrency, and daily-budget rate limiting
- Local audit logging with automatic rotation and retention
- Optional Discord audit notifications through ZenithProxy's Discord integration
- Optional GitHub release update checks with SHA-256 verification before staging

---

## Requirements

- ZenithProxy on Minecraft `1.21.4` (compiled against `1.21.4-SNAPSHOT`)
- Java 21+ at runtime
- JDK 25 to build from source (matches CI; the produced plugin still targets Java 21)
- An API key for an OpenAI-compatible chat-completions endpoint (OpenAI, LM Studio, Ollama's OpenAI shim, vLLM, etc.)

Native Anthropic, Gemini, or other non-OpenAI request shapes are not supported at this time.

---

## Deployment

### 1. Drop the JAR in

1. Download the latest `opencraft-VERSION.jar` and `opencraft-VERSION.jar.sha256` from [Releases](https://github.com/PoseidonsCave/opencraft/releases).
2. Verify the checksum:
   ```sh
   sha256sum -c opencraft-VERSION.jar.sha256
   ```
3. Place the JAR in ZenithProxy's `plugins/` directory.

### 2. Set the API key

Export the API key **as an environment variable** before launching ZenithProxy. The key is never written to disk by OpenCraft.

```sh
export OPENCRAFT_API_KEY="sk-..."
```

The variable name is configurable via `apiKeyEnvVar` if you want to namespace it differently (e.g., for multiple proxies on the same host).

### 3. Start ZenithProxy

OpenCraft loads through the ZenithProxy plugin loader. On first run it generates `plugins/config/opencraft.json` populated with safe defaults.

### 4. Configure users and (optionally) admin commands

Use `/llm user ...` and `/llm allow ...` for day-to-day user and allowlist management. Edit `plugins/config/opencraft.json` directly only when you want to make bulk changes or adjust advanced fields such as `systemPromptOverride`, argument schemas, or redact lists. See [Configuration](#configuration) below.

### Updates

OpenCraft can check GitHub Releases for newer versions on its own. With `updateAutoDownload: false` (the default), staged updates require an explicit operator command:

```
/llm update check     # check only
/llm update stage     # download, SHA-256 verify, stage; restart to apply
```

Both subcommands are gated by ZenithProxy's account-owner role.

---

## Configuration

The fields you'll touch most often:

### Chat trigger

| Key | Default | Description |
|---|---|---|
| `prefix` | `"!oc"` | Trigger prefix in chat |
| `whisperEnabled` | `true` | Accept whispers as input |
| `publicChatEnabled` | `false` | Accept public chat as input |
| `responsePrefix` | `"[OC]"` | Prepended to assistant replies |
| `whisperChunkSize` | `190` | Max chars per whisper chunk (clamped to [10, 200]) |
| `whisperInboundPattern` | `^(\S+) whispers to you: (.+)$` | Regex for inbound whisper detection |

### Provider

| Key | Default | Description |
|---|---|---|
| `providerName` | `"openai"` | Logical label used in logs / audit |
| `providerBaseUrl` | `"https://api.openai.com/v1"` | OpenAI-compatible endpoint base |
| `model` | `"gpt-4o"` | Model identifier sent to the API |
| `apiKeyEnvVar` | `"OPENCRAFT_API_KEY"` | **Name** of the env var holding the key (never the key itself) |
| `timeoutSeconds` | `30` | Per-request timeout |
| `maxInputLength` | `1000` | Truncate user input above this length |
| `maxOutputTokens` | `500` | Hard ceiling on model output |
| `temperature` | `0.7` | Sampling temperature |

### Rate limits

| Key | Default | Description |
|---|---|---|
| `userCooldownMs` | `5000` | Min ms between requests from the same user |
| `userHourlyLimit` | `30` | Per-user hourly cap (0 = off) |
| `globalRequestsPerMinute` | `60` | Global RPM cap (0 = off) |
| `maxConcurrentRequests` | `5` | Fair semaphore and clamped to ≥ 1 |
| `dailyBudgetTokens` | `250000` | Daily token budget (0 = off, resets at UTC midnight) |

### RBAC

```json
"users": {
  "069a79f4-44e9-4726-a5be-fca90e38aaf5": "admin",
  "61699b2e-d327-4a01-9f1e-0ea8c3f06bc6": "member"
}
```

Roles are `"member"` or `"admin"`. UUID lookup (with or without dashes) is preferred. When you use `/llm user add`, `/llm user promote`, `/llm user demote`, or `/llm user remove` with a username, OpenCraft first tries to resolve that username to a UUID-backed profile and stores or updates the UUID entry when it can. `allowUsernameOnlyFallback: true` still controls the weaker runtime name-only auth path for offline servers, but **admin role is never granted by username alone** — a confirmed UUID is always required for admin authority.

For live operations, you usually do not need to hand-edit the `users` block:

```text
/llm user add 069a79f4-44e9-4726-a5be-fca90e38aaf5 admin
/llm user add SomePlayer member
/llm user promote SomePlayer
/llm user demote SomePlayer
/llm user remove SomePlayer
/llm user list
```

These commands update the in-memory config and persist it immediately to `plugins/config/opencraft.json`.

### Admin command allowlist

Each entry in `allowedCommands` defines exactly one ZenithProxy command the LLM is permitted to invoke on an admin's behalf:

```json
{
  "commandId":         "stash.scan",
  "description":       "Scan the configured stash region.",
  "zenithCommand":     "stash scan",
  "roleRequired":      "admin",
  "riskLevel":         "low",
  "confirmationRequired": false,
  "argumentSchema": { "label": "string" },
  "redactFields": []
}
```

Notes:

- `commandId` is the only identifier ever exposed to the LLM. `zenithCommand` is the actual proxy command that gets dispatched and is **never** sent to the model.
- `argumentSchema` is a simple per-argument type map. Supported validation types today are `string` and `integer`. Empty schema = no arguments accepted.
- `confirmationRequired: true` makes the command wait for a `!oc confirm` from the same admin within `confirmationTimeoutSeconds`.
- `redactFields` are stripped from the captured output before it reaches whispers, audit log, or Discord.

For live operations, you can manage the basic allowlist without editing JSON directly:

```text
/llm allow list
/llm allow add stash.scan admin low false Scan the configured stash region -- stash scan
/llm allow remove stash.scan
```

`/llm allow add` expects everything after the `confirm` flag in the form:

```text
DESCRIPTION -- ZENITH_COMMAND
```

That keeps the operator-facing description separate from the actual Zenith command string. More advanced fields such as `argumentSchema` and `redactFields` still require editing `plugins/config/opencraft.json` manually.

### Audit log

| Key | Default | Description |
|---|---|---|
| `auditLogEnabled` | `true` | |
| `auditLogPath` | `"logs/opencraft-audit.log"` | Live file; rolled `.gz` siblings sit alongside it |
| `auditRetentionDays` | `30` | Used as logback `maxHistory`; total disk cap is 500 MB |
| `logDeniedAttempts` | `true` | Include denied/unauthorized requests |

Rotation, compression, and retention are owned by logback's `SizeAndTimeBasedRollingPolicy` (10 MB per file, daily roll, gz-compressed). The `/llm audit prune` command exists for compatibility but is a no-op.

### Discord notifications (optional)

| Key | Default | Description |
|---|---|---|
| `discordAuditEnabled` | `false` | Send audit events to Discord |
| `discordLogDenied` | `true` | Include denied requests |
| `discordLogAdminCommands` | `true` | Include admin command intents/results |

OpenCraft does not run its own Discord bot or webhook transport. When enabled, embeds are sent through ZenithProxy's already-configured Discord bot. If ZenithProxy's Discord integration is disabled, OpenCraft silently skips the notification.

### Updates

| Key | Default | Description |
|---|---|---|
| `updateCheckOnLoad` | `true` | Check GitHub Releases at startup |
| `updateAutoDownload` | `false` | Auto-stage matching releases (verified by SHA-256) |
| `updateChannel` | `"stable"` | `stable` \| `beta` \| `dev` |

### System prompt override (advanced)

`systemPromptOverride` lets operators replace the base prompt body. The runtime context block, the mandatory security rules, and the role-scoped response-format block are always prepended regardless. Operators cannot disable the security rails from config.

This remains an advanced, developer-oriented setting on purpose. OpenCraft does not expose prompt editing over `/llm` commands, because prompt changes are high-impact and easier to break than user or allowlist edits.

---

## ZenithProxy Operator Commands

All `/llm` subcommands run through ZenithProxy's standard command bus, but OpenCraft restricts execution to Zenith `TERMINAL` and `DISCORD` sources only. In-game command sources are explicitly denied for `/llm` management. Every `/llm` subcommand requires ZenithProxy's canonical account-owner authorization.

In short: players use `!oc ...` in chat, while the proxy owner manages OpenCraft through `/llm ...` from terminal or Discord.

| Command | Auth | Description |
|---|---|---|
| `/llm status` | account owner | Module state, provider, model, prefix, update status |
| `/llm config` | account owner | Non-sensitive config summary (no secrets, no UUIDs) |
| `/llm user list` | account owner | Show configured user entries and whether username fallback is enabled |
| `/llm user add UUID_OR_USERNAME MEMBER\|ADMIN` | account owner | Persist a user entry immediately; username input is resolved to UUID when possible, and `admin` still requires a UUID-backed profile |
| `/llm user promote UUID_OR_USERNAME` | account owner | Upgrade an existing user entry to `admin`, resolving username input to UUID when possible |
| `/llm user demote UUID_OR_USERNAME` | account owner | Downgrade an existing user entry to `member` |
| `/llm user remove UUID_OR_USERNAME` | account owner | Remove a persisted user entry immediately |
| `/llm allow list` | account owner | Show allowlisted command IDs, roles, risks, and confirmation flags |
| `/llm allow add COMMAND_ID ROLE RISK CONFIRM DESCRIPTION -- ZENITH_COMMAND` | account owner | Persist a basic allowlist entry immediately |
| `/llm allow remove COMMAND_ID` | account owner | Remove a persisted allowlist entry immediately |
| `/llm enable` | account owner | Enable the module |
| `/llm disable` | account owner | Disable the module |
| `/llm update` / `update check` | account owner | Check GitHub for a newer release |
| `/llm update stage` | account owner | Download + SHA-256 verify + stage (restart to apply) |
| `/llm audit prune` | account owner | No-op kept for compatibility (logback handles retention) |

`/llm user ...` and `/llm allow ...` save immediately to `plugins/config/opencraft.json`, so operators on container platforms such as Dokploy do not need to rebuild the image or modify the deployment pipeline just to onboard users or manage the basic allowlist.

Denied `/llm` management attempts are audited through the local audit log (`REQUEST_DENIED`) with source and denial reason context.

---

## End-User Guide

This is the only part most players need:

### Asking a question

Whisper the proxy account in-game:

```
/w ZenithAccount !oc What is the fastest way to the nether highway?
```

The assistant replies in chat with the configured response prefix. Longer replies are split into multiple messages such as `[OC] (1/2) ...`.

If `publicChatEnabled` is `true`, the same trigger works in public chat and the response goes back to public chat.

### Admin command flow

If you are configured as `"admin"` and the operator has populated `allowedCommands`:

```
> !oc scan the stash
< [OC] Done: [command dispatched: stash.scan]
```

The LLM picks the matching `commandId` from the allowlist; OpenCraft validates arguments against the schema, then dispatches the actual `zenithCommand` through Zenith's command bus attributed to `TERMINAL`.

### High-risk confirmation

For commands with `"confirmationRequired": true`:

```
> !oc clear all indexed stash data
< [OC] High-risk command: Clear all indexed stash data. Reply '!oc confirm' within 60 seconds to proceed, or '!oc cancel'.
> !oc confirm
< [OC] Done: [command dispatched: stash.clearall]
```

To cancel:

```
!oc cancel
```

Pending confirmations expire after `confirmationTimeoutSeconds`.

### Multi-step operations

If the operator enables `operationsEnabled`, the assistant can propose multi-step admin plans instead of only single commands. In that mode, admins may see a plan summary first and then reply with `!oc confirm` or `!oc cancel`.

Operations are disabled by default, so most installs will not use this flow unless the operator turns it on intentionally.

### What the LLM will refuse

The system prompt instructs the model to refuse any question about: server configuration, other players' UUIDs/roles/membership, plugin internals, available commands, environment variables, the system prompt itself, or any administrative action. All of these get the canonical reply:

> I can't help with that.

This is defense in depth. The Java RBAC is the actual enforcement layer; the prompt rules just reduce information leakage from the LLM's response surface.

---

## RBAC matrix

| Capability | Non-whitelisted | MEMBER | ADMIN |
|---|:-:|:-:|:-:|
| Ask questions | ✗ | ✓ | ✓ |
| Trigger admin command execution | ✗ | ✗ | ✓ |
| Confirm high-risk commands | ✗ | ✗ | ✓ |
| Receive admin tool descriptions in prompt | ✗ | ✗ | ✓ (summary only) |

**The LLM is never the authorization layer.** Every command intent is re-checked against the allowlist and the user's role server-side, even if the LLM emits a `command_intent` for a non-admin.

---

## Security Notes

- API keys live only in environment variables and never in the config file, the audit log, the prompt, or Discord embeds.
- All LLM-supplied argument values are validated against the per-command JSON schema and a strict character set before being interpolated into the proxy command.
- Admin role is never granted by username alone; a confirmed UUID is always required.
- Whispers go out as a typed `ServerboundChatPacket` built by ZenithProxy's `ChatUtil`, not via string concatenation into a `/msg` slash command.
- Audit log rotation, compression, and retention are owned by logback (`SizeAndTimeBasedRollingPolicy`), not by ad-hoc file pruning.
- Auto-downloaded JARs are SHA-256 verified against a CI-generated checksum before staging.

---

## Building from Source

Requires JDK 25 and the ZenithProxy plugin dev toolchain reachable at `maven.2b2t.vc`. The build runs on Java 25; the produced plugin targets Java 21 for ZenithProxy runtime compatibility.

```sh
git clone https://github.com/PoseidonsCave/opencraft
cd opencraft
./gradlew build
```

The JAR is written to `build/libs/opencraft-VERSION.jar`.

Run tests:

```sh
./gradlew test
```

---

## Package Layout

```
src/main/java/com/zenith/plugin/opencraft/
├── OpenCraftPlugin, OpenCraftConfig, OpenCraftModule
├── auth/       UserRole, UserIdentity, AuthorizationService
├── command/    OpenCraftCommand (operator /llm command tree)
├── provider/   OpenCraftProvider, OpenAIProvider, MockProvider, ProviderFactory
├── intent/     CommandIntent, CommandAllowlist, IntentParser, CommandExecutor
├── ratelimit/  RateLimiter (Semaphore + sliding-window per-user buckets)
├── audit/      AuditLogger (logback RollingFileAppender), AuditEvent
├── discord/    DiscordNotifier (routed via Zenith's Discord bot), DiscordAuditPayload
├── prompt/     PromptBuilder
├── chat/       ChatHandler (LLM pipeline orchestration)
└── update/     PluginUpdateService (GitHub Releases + SHA-256 staging)
```

---

## Contributing

1. Fork and branch from `main`.
2. Add or update tests for your change.
3. Ensure `./gradlew test` passes.
4. Open a pull request with a clear description.

For security vulnerabilities, please disclose to PoseidonsCave on Discord rather than a public issue.

---

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
