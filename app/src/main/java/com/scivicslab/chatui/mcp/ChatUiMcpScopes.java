package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.ConversationToolCatalog;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationToolRunner;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.pojoactor.core.ActorRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * chat-ui's conversations as the scopes of agent-connector-kernel's MCP server at {@code /mcp}
 * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}; tasks/todo.md, Decision 12).
 *
 * <ul>
 * <li>The tools are every harness tool from {@link ConversationToolCatalog}, and the plugin tools. A conversation whose
 * runner runs its calls (the openai-compat provider's MCP client) is offered only those that do not drive it.</li>
 * <li>A conversation whose provider parks the calls of its harness CLI ({@link LlmProvider#parksToolCalls}) has every
 * call handed to that provider, which turns it into a step of the conversation's own agent loop; the request waits for
 * the result. Such a conversation is offered the tools that drive it as well (tasks/todo.md, "Shrinking the composed
 * prompt").</li>
 * <li>A scope's name is a conversation's qualified name {@code <project>/<chat>}, sent in agent-connector-kernel's scope header
 * ({@code X-Agent-Connector-Scope}). Its runner is the one the conversation
 * last published.</li>
 * <li>Every request carries its conversation's key ({@link ChatUiActorSystem#mcpKeyOf}); agent-connector-kernel's
 * default {@code agent-connector-kernel.mcp.require-key=true} applies (tasks/todo.md, "Shrinking the composed
 * prompt": once the tools that drive a conversation are offered, a request must prove which conversation sent it).</li>
 * </ul>
 */
@ApplicationScoped
public class ChatUiMcpScopes implements McpScopes {

    @Inject
    ChatUiActorSystem chats;

    @Override
    public List<ToolCatalog.Spec> tools() {
        List<ToolCatalog.Spec> specs = new ArrayList<>();
        specs.addAll(ConversationToolCatalog.builtInTools().values());
        chats.getPluginRegistry().tools().forEach(t -> specs.add(ToolCatalog.ofPlugin(t)));
        return specs;
    }

    @Override
    public ToolRunner runnerOf(String name) {
        ConversationToolRunner runner = chats.toolRunnerOf(name);
        return runner == null ? null : runner.core();
    }

    @Override
    public boolean opens(String name, String key) {
        return chats.opensMcp(name, key);
    }

    @Override
    public McpCallHandOff handOffOf(String name) {
        ConversationToolRunner runner = chats.toolRunnerOf(name);
        ActorRef<LlmProvider> provider = runner == null ? null : chats.providerRefOf(name);
        if (provider == null || !parks(provider)) return null;
        return new McpCallHandOff() {
            @Override
            public boolean offers(String tool) {
                return runner.offersToItsOwnLoop(tool);
            }

            @Override
            public String call(String tool, String argumentsJson) {
                try {
                    // askNow: the provider's actor is busy in sendPrompt, which waits for this very call
                    return provider.askNow(p -> p.parkToolCall(tool, argumentsJson)).get().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "error: interrupted while the conversation ran the call";
                } catch (Exception e) {
                    return "error: " + e.getMessage();
                }
            }
        };
    }

    private static boolean parks(ActorRef<LlmProvider> provider) {
        try {
            return provider.askNow(LlmProvider::parksToolCalls).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
