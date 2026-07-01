package com.scivicslab.chatui.app.workspace;

import com.scivicslab.aiworkspace.spi.WorkspaceToolPlugin;

import java.util.List;

public final class WorkspacePlugin implements WorkspaceToolPlugin {

    @Override public String toolName()       { return "quarkus-chat-ui"; }
    @Override public String jarFileName()    { return "quarkus-chat-ui.jar"; }
    @Override public int defaultPort()       { return 28100; }
    @Override public String githubRepo()     { return "scivicslab/quarkus-chat-ui"; }
    @Override public String gatewayMcpProp() { return "chat-ui.agent-loop.mcp-urls"; }

    @Override
    public List<ParamDef> params() {
        return List.of(
            new ParamDef("workdir", "Working Directory", "dir",
                "${HOME}/works", null, true, -1, List.of()),
            new ParamDef("provider", "LLM Provider", "select",
                "${DEFAULT_PROVIDER}", "chat-ui.provider", false, -1, List.of(
                    new ParamOption("claude",        "Claude"),
                    new ParamOption("codex",         "Codex (OpenAI)"),
                    new ParamOption("openai-compat", "Local LLM (vLLM)")
                )),
            new ParamDef("servers", "vLLM Endpoint (Local LLM only)", "text",
                "${VLLM_ENDPOINT}", "chat-ui.servers", false, -1, List.of()),
            new ParamDef("allowed-dirs", "Allowed Directories (comma-separated)", "text",
                "${HOME}/works", "chat-ui.filesystem.allowed-dirs", false, -1, List.of()),
            new ParamDef("port", "Port (blank = auto-assign)", "text",
                "", null, false, -1, List.of())
        );
    }
}
