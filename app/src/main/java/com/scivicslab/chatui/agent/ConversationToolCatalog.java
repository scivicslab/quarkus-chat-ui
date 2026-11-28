package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.agent.ToolCatalog.Spec;
import com.scivicslab.chatui.plugin.ToolParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The built-in tools a conversation is shown, in the order the model sees them
 * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * <p>The tools agent-connector-kernel runs itself ({@code read}, {@code calc}, {@code search_docs},
 * {@code list_references}, {@code write}, {@code remove}) are defined in agent-connector-kernel's {@link ToolCatalog}
 * and placed here where they always stood. Every other built-in tool works on ConversationSquad actors or the actor
 * tree, so it is defined here, with the screen and the record.</p>
 */
public final class ConversationToolCatalog {

    private static final Map<String, Spec> BUILT_IN = builtIn();

    private ConversationToolCatalog() {
    }

    /** @return the built-in tools, keyed by name, in the order the model is shown them */
    public static Map<String, Spec> builtInTools() {
        return BUILT_IN;
    }

    /** @return the built-in tool {@code name}, or {@code null} when there is none */
    public static Spec builtIn(String name) {
        return BUILT_IN.get(name);
    }

    /** @return the names of the built-in tools that drive the conversation */
    public static List<String> conversationDrivingTools() {
        return BUILT_IN.values().stream().filter(Spec::drivesConversation).map(Spec::name)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static void add(Map<String, Spec> m, String name, String description, List<ToolParameter> parameters,
                            boolean drivesConversation) {
        m.put(name, ToolCatalog.define(name, description, parameters, drivesConversation));
    }

    private static Map<String, Spec> builtIn() {
        Map<String, Spec> m = new LinkedHashMap<>();
        // First in the list: it is read before any tool a model might reach for instead of asking
        // (AskThePersonEndsTheTurn_261119_oo01). Never run by runTool: stepExpectingAction reads it.
        add(m, "ask_person", """
                ask the person a question and end your turn at once; use it when you need a decision or answer
                before you can go on. Write what the person needs to know before the call, in the same reply;
                put the question in "question". No other tool in that reply runs. A conversation that is not the
                liaison asks whoever sent the request it is working on, or its superior when none did.
                """,
                List.of(
                    new ToolParameter("question", "string", true, "")),
                true);
        m.put("read", ToolCatalog.core("read"));
        m.put("calc", ToolCatalog.core("calc"));
        m.put("search_docs", ToolCatalog.core("search_docs"));
        add(m, "list_running_tools", """
                list the tool instances the AI-workspace running this chat-ui is running: name, port, state
                (READY, STARTING, ...), address, title and, for a chat-ui instance, "node <name>", the node name
                ask_chat's "node" takes. Known nodes no row matched come on a last line; without an AI-workspace
                only the known node names are listed. "tool" optionally limits it to one tool's instances.
                Read-only.
                """,
                List.of(
                    new ToolParameter("tool", "string", false, "")),
                false);
        m.put("list_references", ToolCatalog.core("list_references"));
        m.put("write", ToolCatalog.core("write"));
        m.put("edit", ToolCatalog.core("edit"));
        m.put("remove", ToolCatalog.core("remove"));
        add(m, "ask_chat", """
                send an instruction to another conversation. chatId: in your own project "chat-02"; in another
                project of this chat-ui instance "project1/chat-01", with no node. Every project shown on this
                instance's screen is on this instance, so a conversation of this instance, in any project, takes
                no node. On another chat-ui instance, to find the node name of another instance, call
                list_running_tools first; give it as "node" and write chatId with its project first, e.g.
                "project1/chat-01"; with node, a chatId without its project ("chat-01") is refused.
                It does NOT wait: the result names the conversation and the request id (e.g.
                "project1/chat-01:s40:t5:r2"). The reply reaches you only AFTER YOU END YOUR TURN, as a new
                message "[reply to request <request id>, from <turn>]" with a line [ended: answered; model calls:
                N; tool calls: M] saying how its turn ended: "answered", "step-limit", "time-limit",
                "repeated-call" (the last three: the reply is what it found so far, possibly unfinished) or
                "asked" (it ended with ask_person: the reply is a question waiting for your decision, answer it
                with ask_chat or ask_person). So once you have sent your instructions, end your turn; do not poll
                get_chat_log or get_chat_status for the reply, since it cannot arrive while your turn goes on.
                Several replies come one per turn.
                Declare thinking every time with "think" (boolean): true asks the receiver to think before it answers
                this one request, for a hard question; false, or leaving "think" out, asks it to answer without
                thinking, faster and cheaper for a short question or status report. The receiver's own setting does
                not apply.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("prompt", "string", true, ""),
                    new ToolParameter("node", "string", false, ""),
                    new ToolParameter("think", "boolean", false, "")),
                true);
        add(m, "ask_chat_urgent", """
                as ask_chat (same chatId, node and think: declare it every time; leaving it out means no think): a conversation of this instance takes no node
                ("project1/chat-01"); to find the node name of another instance, call list_running_tools first.
                The request goes into the other conversation's queue ahead of every waiting message that is not
                urgent, and its reply comes back to you the same way. It does not interrupt a turn the other
                conversation is running; it is answered next. Use it when someone asks how the work you handed
                out is going: send each conversation you gave work to an urgent request asking, in one paragraph,
                how far it has got (it should answer only that and keep its work), then end your turn. When
                every answer is back, sum up how far each one has got.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("prompt", "string", true, ""),
                    new ToolParameter("node", "string", false, ""),
                    new ToolParameter("think", "boolean", false, "")),
                true);
        add(m, "cancel_request", """
                take back a request you sent with ask_chat or ask_chat_urgent while it still waits in the other
                conversation's queue. "requestId" is the id the ask_chat result gave. "node" is only for a
                request sent to another chat-ui instance (to find the node name of another instance, call
                list_running_tools first); a request to a conversation of this instance, in any project, takes
                no node. The result is "removed" (no reply will come), "already running" (not stopped; send a
                follow-up if the work is no longer wanted), "already answered" or "not found". Use it when the
                work is no longer wanted or you are about to send the same request again.
                """,
                List.of(
                    new ToolParameter("requestId", "string", true, ""),
                    new ToolParameter("node", "string", false, "")),
                true);
        add(m, "set_chat_timekeeper", """
                change the timekeeper of another conversation of this project, or your own. urgentWaitMinutes:
                how long an urgent request may wait during a long turn before the turn is paused to answer it
                (default 10). childReplyWarnMinutes: how long a request it sent may go without a reply before it
                is told (default 5). Give at least one, in whole minutes; one left out keeps its value. 0 turns
                that watch off.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("urgentWaitMinutes", "integer", false, ""),
                    new ToolParameter("childReplyWarnMinutes", "integer", false, "")),
                false);
        add(m, "accept_reply", """
                record that the reply to your request "requestId" is enough. When a turn of yours begins with a
                reply ([reply to request …] or [follow-up to request …]), record one decision on it before you
                end the turn: accept_reply, expect_report, or ask_chat to the one that replied.
                """,
                List.of(
                    new ToolParameter("requestId", "string", false, "")),
                true);
        add(m, "expect_report", """
                when a reply says a report will come later (e.g. "not finished yet, about 20 more minutes"),
                register it with that conversation's timekeeper. After "minutes" (whole minutes) it is reminded
                once, and its answer comes to you as [follow-up to request …, reminder N]; if that says "not
                yet" again, register again. A conversation of this instance, in any project, takes no node. Give
                "node" only when the conversation that promised the report is on another chat-ui instance (to
                find the node name of another instance, call list_running_tools first), and then write chatId
                with its project first, e.g. "project1/chat-01". Do not register a report you promise yourself:
                the end of your turn decides that.
                """,
                List.of(
                    new ToolParameter("chatId", "string", false, ""),
                    new ToolParameter("requestId", "string", true, ""),
                    new ToolParameter("minutes", "integer", true, ""),
                    new ToolParameter("node", "string", false, "")),
                true);
        add(m, "set_chat_name", """
                give a conversation of this project, your own included, the name a person reads beside its id,
                saying what it is for (e.g. "worker 1-3: Wikipedia articles A-F"). The id does not change. A
                blank name clears it; at most 80 characters.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("name", "string", false, "")),
                false);
        add(m, "set_agent_loop", """
                replace another ConversationSquad's agent loop, the Turing workflow that runs each of its turns,
                with the given YAML text (not a file path); then start a turn under it with ask_chat.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("yaml", "string", true, "")),
                false);
        add(m, "load_skill", """
                read one of the skills listed after this tool list in full. When one applies to the task at hand,
                load it before you act.
                """,
                List.of(
                    new ToolParameter("name", "string", true, "")),
                false);
        add(m, "set_collaborator", """
                record that, for ConversationSquad "chatId", the one playing role "role" (e.g. "worker") is
                "collaboratorChatId"; an agent loop from set_agent_loop can resolve the role instead of a fixed
                chat id, and calling this again reassigns it. A conversation of this instance, in any project,
                takes no node. Give "node" only when the collaborator is on another chat-ui instance (to find
                the node name of another instance, call list_running_tools first); then write collaboratorChatId
                with its project first, e.g. "project1/chat-01".
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("role", "string", true, ""),
                    new ToolParameter("collaboratorChatId", "string", true, ""),
                    new ToolParameter("node", "string", false, "")),
                false);
        add(m, "get_chat_status", """
                report which model, provider and tool set another conversation is on, and whether it is busy. It
                answers even while that conversation runs a turn. "chatId" is e.g. "02", or "project2/02" for
                another project.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "list_chat_models", """
                list the models another conversation's provider offers, one per line as name (type, server);
                set_chat_model takes one of these names.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "set_chat_model", """
                put another conversation of this project on a different model, taking effect after its current
                turn. The name must be one list_chat_models printed; any other is refused with the list, and so
                is a conversation of another project.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("model", "string", true, "")),
                false);
        add(m, "list_providers", """
                list the providers a conversation of this instance can be put on, one per line as value (label),
                e.g. "openai-compat (Local LLM)"; create_chat and set_chat_provider take the value. No
                parameters.
                """,
                List.of(),
                false);
        add(m, "create_chat", """
                add a new, empty conversation to your own project and return its id (e.g. "06") for the other
                conversation tools. All parameters are optional. "provider": a value list_providers printed
                (default claude, Claude Code); "model": one of its models (default the provider's own); a wrong
                one is refused with the usable names and nothing is created. Name openai-compat (a local LLM)
                only for workers that repeat one kind of job many times, where calls cost nothing and many run at
                once. Instead of provider and model you may give "role" (manager, worker or repeater), which
                decides provider, model, name and timekeeper limits; role with a provider or model is refused.
                The new conversation reports to you; "superior" puts it under another conversation's id, "none"
                under nobody. A superior already at the subordinate limit is refused, naming its subordinates
                with room left.
                """,
                List.of(
                    new ToolParameter("provider", "string", false, ""),
                    new ToolParameter("model", "string", false, ""),
                    new ToolParameter("superior", "string", false, ""),
                    new ToolParameter("role", "string", false, "")),
                false);
        add(m, "list_chats", """
                list the conversations of your project, one per line: id, name, superior, number of
                subordinates, provider and model, busy or idle; you and the liaison (the conversation that talks
                with the person) are marked. No parameters.
                """,
                List.of(),
                false);
        add(m, "set_superior", """
                make "superiorChatId" the superior of conversation "chatId" of your project, or with "none"
                leave it with none. A conversation reports to its superior and sends it the questions it cannot
                decide. Refused, nothing changed, when it would make a loop, when "chatId" is the liaison, or
                when the superior is at the subordinate limit.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("superiorChatId", "string", false, "")),
                false);
        add(m, "set_chat_provider", """
                put another conversation of this project on a different provider, a value list_providers
                printed, taking effect after its current turn. Its model becomes the provider's default; call
                set_chat_model for another. A conversation of another project is refused.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("provider", "string", true, "")),
                false);
        add(m, "delete_chat", """
                delete a conversation of your own project, e.g. one made with create_chat. Refused, nothing
                changed, for project1's chat-01, the project's last conversation, one answering or with queued
                prompts, one with conversations under it, and your own.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "get_chat_log", """
                read the newest entries of another conversation's log without sending it anything, to check
                once what it is doing or why a call to it failed. "count": default 20, at most 200.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("count", "integer", false, "")),
                false);
        add(m, "get_request_messages", """
                read the messages array another conversation will send to its model next, as a JSON array of
                {"role","content"}; this is what the model reads, not the screen's record. Refused while it is
                answering.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "set_request_messages", """
                replace the whole messages array another conversation of this project will send next;
                "messages" is a JSON array of {"role","content"} with roles system, user and assistant. A
                malformed or too long array is refused, nothing changes. To drop one message, read the array,
                remove it, set the result.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("messages", "string", true, "")),
                false);
        add(m, "complete_messages", """
                call the model of another conversation once with exactly the given messages and return its
                reply; nothing is added to its history, and it works while that conversation is answering.
                "model" and "noThink" are optional.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("messages", "string", true, ""),
                    new ToolParameter("model", "string", false, ""),
                    new ToolParameter("noThink", "boolean", false, "")),
                false);
        // ExternalMcpServersOfAConversation_261120_oo01
        add(m, "set_mcp_servers", """
                register the external MCP servers a conversation of this project uses, replacing its old ones.
                "servers" is a JSON object from a server name (a lowercase letter, then lowercase letters,
                digits or '-', at most 20) to its MCP endpoint URL, e.g. {"htmlsaurus":
                "http://localhost:28001/mcp"}; {} removes all. From its next turn a local LLM there gets each
                server's tools as <server>__<tool>.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("servers", "string", true, "")),
                false);
        return Collections.unmodifiableMap(m);
    }
}
