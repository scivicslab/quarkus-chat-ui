# quarkus-chat-ui

A multi-provider chat UI for Large Language Models, built with [Quarkus](https://quarkus.io/) and [POJO-actor](https://github.com/scivicslab/POJO-actor).

## Features

- **Multiple LLM providers** — Claude CLI, Codex CLI, and OpenAI-compatible APIs (vLLM, Ollama)
- **Streaming responses** — Server-Sent Events (SSE) for real-time token streaming
- **Prompt queue** — Queue multiple prompts and execute them sequentially
- **Theme support** — 10 built-in themes (dark and light variants) with localStorage persistence
- **Slash commands** — Provider-specific commands (e.g., `/model`, `/compact`, `/clear`)
- **Keyboard modes** — Default, Emacs, and Vi keybindings
- **Context overflow recovery** — Automatic history trimming when the context window is exceeded
- **Watchdog monitoring** — Detects and recovers from stalled LLM processes
- **URL fetch** — Fetches and extracts text from URLs for inclusion in prompts

## Architecture

```
quarkus-chat-ui/
├── core/                       # Actor system, REST API, SSE streaming
├── provider-cli/               # Base CLI provider (process management, stream parsing)
├── provider-claude/            # Claude Code CLI adapter
├── provider-codex/             # OpenAI Codex CLI adapter
├── provider-openai-compat/     # OpenAI-compatible HTTP API (vLLM, Ollama)
└── app/                        # Quarkus application assembly + static web UI
```

The application uses the **actor model** via POJO-actor for managing chat sessions, watchdog timers, and queue processing. Each chat session is handled by a `ChatActor` that serializes all operations on a single thread, eliminating concurrency bugs.

## Prerequisites

- Java 21+
- Maven 3.9+
- One of the supported LLM backends:
  - [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) with `ANTHROPIC_API_KEY`
  - [OpenAI Codex CLI](https://github.com/openai/codex) with `OPENAI_API_KEY`
  - A running [vLLM](https://github.com/vllm-project/vllm) or [Ollama](https://ollama.ai/) server

## Build

```bash
rm -rf target
mvn install
```

The runnable JAR is produced at `app/target/quarkus-app/quarkus-run.jar`. Copy it to a stable location:

```bash
cp app/target/quarkus-app/quarkus-run.jar ~/bin/chat-ui.jar
```

## Run

```bash
# Claude provider (default)
java -Dchat-ui.provider=claude \
     -Dquarkus.http.port=28010 \
     -jar ~/bin/chat-ui.jar

# OpenAI-compatible provider (vLLM / Ollama)
java -Dchat-ui.provider=openai-compat \
     -Dchat-ui.servers=http://localhost:8000 \
     -Dquarkus.http.port=28010 \
     -jar ~/bin/chat-ui.jar
```

Open `http://localhost:28010` in your browser.

## Configuration

Key properties (set via `-D` flags or `application.properties`):

| Property | Default | Description |
|----------|---------|-------------|
| `chat-ui.provider` | `claude` | LLM provider: `claude`, `codex`, or `openai-compat` |
| `chat-ui.servers` | `http://localhost:8000` | Comma-separated server URLs (openai-compat only) |
| `chat-ui.default-model` | *(auto-detect)* | Default model name |
| `chat-ui.keybind` | `default` | Keyboard mode: `default`, `mac`, or `vim` |
| `chat-ui.title` | `Coder Agent` | Browser tab and header title |
| `chat-ui.api-key` | *(env var)* | API key (prefer `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env vars) |
| `quarkus.http.port` | `8090` | HTTP listen port |

## Testing

### Unit tests

Pure Java tests with no external dependencies. No `@QuarkusTest`, no Docker, no DevServices.

```bash
mvn test
```

### E2E tests (Java Playwright)

Browser-based tests against a running instance. Uses `maven-failsafe-plugin` with `*IT.java` naming.

```bash
# First time only: install Chromium
PW_HOME=~/.m2/repository/com/microsoft/playwright
java -cp "$PW_HOME/playwright/1.52.0/playwright-1.52.0.jar:$PW_HOME/driver/1.52.0/driver-1.52.0.jar:$PW_HOME/driver-bundle/1.52.0/driver-bundle-1.52.0.jar" \
  com.microsoft.playwright.CLI install chromium

# Run E2E tests (app must be running)
mvn verify -pl app -am -Dchat-ui.e2e.base-url=http://localhost:28010
```

### Test summary

| Type | Classes | Tests | Framework |
|------|---------|-------|-----------|
| Unit (`*Test.java`) | 14 | 295 | JUnit 5 |
| E2E (`*IT.java`) | 6 | 31 | Playwright 1.52 |

## License

[Apache License 2.0](LICENSE)
