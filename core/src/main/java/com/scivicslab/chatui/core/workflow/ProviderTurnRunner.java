package com.scivicslab.chatui.core.workflow;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * quarkus-chat-ui's {@link TurnRunner}: one turn is one {@link LlmProvider#sendPrompt} call. The
 * turn's events are forwarded to the browser with the turn's own {@code busy=false} removed (the
 * outer run is still going; only its single terminal result may end the browser's busy state), and
 * the turn is recorded in the I/O log in the marker format the Sessions tab reads.
 */
public class ProviderTurnRunner implements TurnRunner {

    private static final Logger LOG = Logger.getLogger(ProviderTurnRunner.class.getName());

    private final LlmProvider provider;
    private final Consumer<ChatEvent> emitter;
    private final IoLogStore ioLog;
    private final long ioSession;

    public ProviderTurnRunner(LlmProvider provider, Consumer<ChatEvent> emitter, IoLogStore ioLog) {
        this.provider = provider;
        this.emitter = emitter;
        this.ioLog = ioLog;
        this.ioSession = (ioLog != null) ? ioLog.ensureSession() : -1;
    }

    @Override
    public String run(String instruction) {
        int turnNo = (ioLog != null) ? ioLog.beginTurn() : 0;
        StringBuilder assistant = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Consumer<ChatEvent> turnEmitter = ev -> {
            if ("delta".equals(ev.type()) && ev.content() != null) {
                assistant.append(ev.content());
            } else if ("thinking".equals(ev.type()) && ev.content() != null) {
                thinking.append(ev.content());
            }
            emitter.accept("result".equals(ev.type()) ? ev.withoutBusy() : ev);
        };
        try {
            // Blocks until the turn's result event: the next workflow step waits for the reply.
            provider.sendPrompt(instruction, provider.getCurrentModel(), turnEmitter, ProviderContext.simple(null));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "workflow turn failed", e);
            emitter.accept(ChatEvent.error("turn failed: " + e.getMessage()));
            throw new IllegalStateException("turn failed: " + e.getMessage(), e);
        }
        recordTurn(turnNo, instruction, assistant.toString(), thinking.toString());
        return assistant.toString();
    }

    private void recordTurn(int turnNo, String prompt, String assistant, String thinkingText) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String requestJson = new org.json.JSONObject()
                    .put("messages", new org.json.JSONArray().put(
                            new org.json.JSONObject().put("role", "user").put("content", prompt)))
                    .toString();
            StringBuilder m = new StringBuilder();
            m.append("REQUEST:\n").append(requestJson);
            m.append("\n\nRESPONSE:\n").append(assistant == null ? "" : assistant);
            if (thinkingText != null && !thinkingText.isBlank()) {
                m.append("\n\nREASONING:\n").append(thinkingText);
            }
            m.append("\n\nUSAGE: promptTokens=0 completionTokens=0");
            ioLog.record(ioSession, "harness", "turn" + turnNo + "/step1/llm", m.toString());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "harness I/O log record failed", e);
        }
    }
}
