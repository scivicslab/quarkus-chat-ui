package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.audittrail.TokenBreakdown.Req;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.pojoactor.core.ActorRef;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Tokens tab's table of what fills the window ({@link TokenBreakdown}), for one conversation: its last request and
 * the sum over its requests, split over fixed rows, with the residual and the special topics. Read-only.
 */
@Path("/api/chats")
public class TokensBreakdownResource {

    /** How many of a session's latest requests the cumulative column adds up at most; each request's body is parsed. */
    static final int MAX_REQUESTS = 2000;

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    IoLogStore ioLog;

    @Inject
    IoLogView ioLogView;

    @Inject
    HeadBlockResource headBlock;

    /** The table of windows of the models whose server does not report one; see {@link WindowLimits}. */
    @ConfigProperty(name = "chat-ui.window-limits")
    java.util.Optional<String> windowLimits;

    /**
     * @param id a qualified conversation name such as {@code project1/chat-02}; the slash may be sent as {@code %2F}
     * @return a {@link TokenBreakdown.Table}, or 404 for no such conversation
     */
    @GET
    @Path("/{id: .+}/tokens-breakdown")
    @Produces(MediaType.APPLICATION_JSON)
    public Response breakdown(@PathParam("id") String id) {
        int at = id.indexOf("/chat-");
        if (at < 0) return notFound(id);
        ChatSessionIIAR session = actorSystem.getChatSession(id.substring(0, at), id.substring(at + "/chat-".length()));
        if (session == null) return notFound(id);
        String provider = session.getProviderIdDirect();
        String model = session.getModelDirect();

        long sessionId = ioLog.sessionIdOf(id);
        List<IoLogView.LlmRow> rows = sessionId < 0 ? List.of() : ioLogView.llmRows(sessionId);
        List<String> notes = new ArrayList<>();
        if (rows.size() > MAX_REQUESTS) {
            rows = rows.subList(rows.size() - MAX_REQUESTS, rows.size());
            notes.add("The cumulative column adds the latest " + MAX_REQUESTS + " requests of the session.");
        }

        List<Req> reqs;
        String basis = "estimated";
        if ("openai-compat".equals(provider)) {
            reqs = TokenBreakdown.openAiRequests(rows);
            notes.add("Each row is estimated from the request body as it was sent; the input is what the server reported."
                    + " The server's /tokenize is not reachable through the configured server (the broker), so no count is exact.");
        } else if ("claude".equals(provider)) {
            reqs = TokenBreakdown.cliRequests(rows, headTokens(id));
            notes.add("Claude Code keeps its own prompt, tools and history; chat-ui sees only its own prompts, the answers and the"
                    + " head-block files, so the rows are estimates from those and the rest is in the residual.");
        } else {
            reqs = TokenBreakdown.cliRequests(rows, headTokens(id));
            for (int i = 0; i < reqs.size(); i++) {
                Req r = reqs.get(i);
                reqs.set(i, new Req(r.turn(), r.step(), r.rows(), null, 0, 0, null, 0));
            }
            notes.add("This provider reports no token usage, so the input is not measured and the residual is not shown.");
        }
        // The window, best source first: what the provider reported for this conversation's resolved model (Claude Code's
        // modelUsage, recorded in the I/O log), then what the server reports (a local LLM), then the table, which only the
        // model's name decides and so is shown as an estimate.
        Integer limit = null;
        String limitSource = "not set (add the model to chat-ui.window-limits)";
        String windowBasis = "measured";
        IoLogView.ModelLimits recorded = sessionId < 0 || !"claude".equals(provider) ? null : ioLogView.modelLimits(sessionId);
        int fromServer = recorded != null && recorded.contextWindow() > 0 ? -1 : windowOfProvider(id);
        if (recorded != null && recorded.contextWindow() > 0) {
            limit = recorded.contextWindow();
            limitSource = "the context window Claude Code reported for " + recorded.model()
                    + " (modelUsage.contextWindow of its result in turn " + recorded.turn() + ")";
        } else if (fromServer > 0) {
            limit = fromServer;
            limitSource = "the server's own value";
        } else {
            int fromTable = WindowLimits.of(windowLimits.orElse(null)).windowOf(model);
            if (fromTable > 0) {
                limit = fromTable;
                windowBasis = "estimated";
                limitSource = TokenBreakdown.WINDOW_FROM_TABLE + " (the provider has not reported this conversation's window, so the model's name"
                        + " decides it)";
            }
        }
        return Response.ok(TokenBreakdown.table(provider, model, limit, limitSource, windowBasis, reqs, basis, notes)).build();
    }

    private static Response notFound(String id) {
        return Response.status(404).entity(Map.of("type", "error", "message", "no such conversation: " + id)).build();
    }

    /** The head-block parts of a conversation as tokens per row ({@link TokenBreakdown#rowOfHeadPart}). */
    private Map<String, Integer> headTokens(String id) {
        Map<String, Integer> head = new LinkedHashMap<>();
        Response r = headBlock.headBlock(id);
        if (r.getEntity() instanceof HeadBlock.Body body) {
            for (HeadBlock.Part p : body.parts()) {
                String row = TokenBreakdown.rowOfHeadPart(p.name());
                if (row != null) head.merge(row, p.estimatedTokens(), Integer::sum);
            }
        }
        return head;
    }

    private int windowOfProvider(String conversation) {
        try {
            ActorRef<LlmProvider> ref = actorSystem.providerRefOf(conversation);
            if (ref == null) return -1;
            // askNow: a running stream holds the provider's mailbox, and this only reads what the server reported.
            return ref.askNow(LlmProvider::contextWindowTokens).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1;
        }
    }
}
