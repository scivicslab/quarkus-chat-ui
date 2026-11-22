# quarkus-chat-ui

quarkus-chat-ui is a web UI in which several LLM conversations work together, and in which every
request, reply and tool call is written to a log database. It is a Quarkus application: one uber-jar,
run on your own computer or a server, talking to OpenAI-compatible LLM servers and, through plugins,
to CLI harnesses such as Claude Code and Codex.

The repository also holds agent-connector-kernel, the part of quarkus-chat-ui that has no screen and
no log, and ssh-connector, an application built on it that lets a confined harness use a remote
cluster over ssh.

## Modules

| Module | Artifact | What it is |
|---|---|---|
| `plugin-api` | `quarkus-chat-ui-plugin-api` | The interfaces the body and the plugin jars share: providers, provider factories and conversation tools |
| `agent-connector-kernel` | `agent-connector-kernel` | The headless part: the plain tools and the tool runner, the MCP server, the LLM proxy, the Podman launchers for harnesses, and the plugin registry |
| `app` | `quarkus-chat-ui` | The body: conversations and their agent loop, the I/O log, the web UI and the `openai-compat` provider |
| `plugin-web-tools` | `quarkus-chat-ui-plugin-web-tools` | `web_search`, `fetch` and `scholar_search` |
| `plugin-shell` | `quarkus-chat-ui-plugin-shell` | A `bash` tool |
| `plugin-harness` | `quarkus-chat-ui-plugin-harness` | Claude Code and Codex as providers |
| `ssh-tools` | `ssh-tools` | Seven ssh tools (`run_command`, `submit_job`, `job_status`, `cancel_job`, `list_remote`, `upload_file`, `download_file`) that run OpenSSH with a fixed argument array |
| `ssh-connector` | `ssh-connector` | A Quarkus application that serves the ssh tools and the LLM proxy on one owner-only Unix socket and starts one harness in a rootless Podman container |

The group id is `com.scivicslab.chatui`. The `e2e` directory holds end-to-end check programs that
run outside the Maven build.

## Build

Java 25 (GraalVM CE) and Maven:

```
rm -rf target */target
mvn install
```

This produces `app/target/quarkus-chat-ui-3.0.0-SNAPSHOT.jar`, the plugin jars under
`plugin-*/target/`, and `ssh-connector/target/ssh-connector-3.0.0-SNAPSHOT.jar`.

## Run

```
java -Dchat-ui.servers=http://localhost:8000 -Dchat-ui.default-model=<model> \
     -jar app/target/quarkus-chat-ui-3.0.0-SNAPSHOT.jar
```

Then open `http://localhost:28030/`. `chat-ui.servers` lists the OpenAI-compatible servers, and
`chat-ui.read-roots` and `chat-ui.write-root` bound what the file tools may read and write. Plugins
are added at start-up by listing their jars in `chat-ui.plugins`; an instance started without a
plugin jar has none of its code.

## History

This repository was the home of quarkus-chat-ui 1.x and 2.x. Their code is on the `legacy-2.x`
branch and under the tags `v1.0.0` to `v2.5.0`. The 3.x line continues chat-ui-with-audit-trail,
whose history is merged in here; its releases are tagged `audit-trail-v1.0.0` to
`audit-trail-v2.2.0`.
