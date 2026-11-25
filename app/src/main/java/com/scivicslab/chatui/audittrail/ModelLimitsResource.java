package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The model a conversation is really running on, as its provider last reported it: the resolved model id, the context
 * window, the most tokens one answer can have, and the effort the conversation ran with. Read-only; the Settings entry of
 * the right pane shows it beside the selectors that change the model and the effort.
 */
@Path("/api/chats")
public class ModelLimitsResource {

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    IoLogStore ioLog;

    @Inject
    IoLogView ioLogView;

    /**
     * @param id a qualified conversation name such as {@code project1/chat-02}
     * @return {@code known} false until a turn of a provider that reports the limits has ended; then {@code known} true with
     *         {@code model}, {@code contextWindow}, {@code maxOutputTokens} (0 when not reported), {@code effort} (empty when
     *         the conversation left the harness on its own default) and the {@code turn} they were recorded in. 404 for no
     *         such conversation.
     */
    @GET
    @Path("/{id: .+}/model-limits")
    @Produces(MediaType.APPLICATION_JSON)
    public Response modelLimits(@PathParam("id") String id) {
        int at = id.indexOf("/chat-");
        ChatSessionIIAR session = at < 0 ? null
                : actorSystem.getChatSession(id.substring(0, at), id.substring(at + "/chat-".length()));
        if (session == null) {
            return Response.status(404).entity(Map.of("type", "error", "message", "no such conversation: " + id)).build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", session.getProviderIdDirect());
        long sessionId = ioLog.sessionIdOf(id);
        IoLogView.ModelLimits limits = sessionId < 0 ? null : ioLogView.modelLimits(sessionId);
        // The record is of the provider that wrote it: a conversation switched to another provider since has none to show.
        boolean known = limits != null && "claude".equals(session.getProviderIdDirect());
        out.put("known", known);
        if (known) {
            out.put("model", limits.model());
            out.put("contextWindow", limits.contextWindow());
            out.put("maxOutputTokens", limits.maxOutputTokens());
            out.put("effort", limits.effort() == null ? "" : limits.effort());
            out.put("turn", limits.turn());
        }
        return Response.ok(out).build();
    }
}
