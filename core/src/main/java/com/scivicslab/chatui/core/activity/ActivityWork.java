package com.scivicslab.chatui.core.activity;

import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what this instance is doing ({@code ActivitySummary_260905_oo01}).
 *
 * <p>The unit here is the conversation, not the project: this product has one {@link ChatActor}, so
 * the answer is that conversation's subject and there is no "and N others" to add. Started with
 * {@code MultiUserExtension}, each user has their own conversation, and those become the parts.</p>
 *
 * <p>Holds no state of its own. {@link ActivityWatcher} holds the answer this produces and decides
 * when to produce another; this class only reads the conversation and asks a model to name its
 * subject. Separate from the watcher because working one out takes a call to a model and must not
 * run on the watcher's mailbox.</p>
 */
@ApplicationScoped
public class ActivityWork {

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    ActivitySummarizer summarizer;

    @Inject
    IoLogStore ioLog;

    @Inject
    IoLogView ioLogView;

    /**
     * How many of a conversation's most recent entries are read.
     *
     * <p>The answer names both the thing being worked on and what is being done to it, and those
     * two are rarely in the same entry: the name is usually settled early and the work is in the
     * last few exchanges. Wide enough to hold both, and not so wide that a subject the
     * conversation has since dropped gets pulled in.</p>
     */
    static final int ENTRIES_READ = 60;

    /** How much of one entry is passed on. A subject does not need whole answers. */
    static final int CHARS_PER_ENTRY = 400;

    /**
     * Reads whatever conversations this instance holds and asks what each one is about.
     *
     * <p>Calls a model, so it takes as long as the broker does. Callers run it off any mailbox.</p>
     *
     * @return the answer to hold until the next one is worked out
     */
    public ActivityAnswer compute() {
        return actorSystem.isMultiUser() ? perUser() : single();
    }

    /** The one conversation this instance holds. */
    private ActivityAnswer single() {
        List<ChatActor.HistoryEntry> entries =
                actorSystem.getChatActor().ask(a -> a.getHistory(ENTRIES_READ)).join();
        // The in-memory buffer starts empty on every restart even though the conversation it belongs
        // to is still on disk (recordHistory has nothing to do with the persisted I/O log). Without
        // this fallback, an instance freshly restarted to pick up a code change reports "no
        // conversation" about a conversation that has been running for weeks.
        if (entries.isEmpty()) {
            entries = fromPersistedLog();
        }
        if (entries.isEmpty()) {
            return new ActivityAnswer("No conversation yet.", Instant.now(), List.of(), false);
        }
        String subject = summarizer.summarise(material(entries));
        return new ActivityAnswer(
                subject == null ? "There is a conversation, but it could not be summarised."
                                : subject,
                Instant.now(), List.of(), subject != null);
    }

    /**
     * Rebuilds recent history from the persisted I/O log, for when the in-memory buffer is empty
     * because the process was restarted rather than because there is truly no conversation yet.
     *
     * <p>This process writes to exactly one H2 file (one per port) and starts exactly one kind of
     * session in it ({@code "chat-ui-conversation"}), so the most recent session in that file is this
     * conversation's, restart or not — no per-conversation lookup is needed the way a multi-session
     * store would require.</p>
     *
     * @return the most recent turns as history entries, oldest first; empty if there is no persisted
     *         session or the log is disabled
     */
    private List<ChatActor.HistoryEntry> fromPersistedLog() {
        var store = ioLog.store();
        if (store == null) {
            return List.of();
        }
        long sessionId = store.getLatestSessionId();
        if (sessionId < 0) {
            return List.of();
        }
        List<IoLogView.TraceTurn> turns = ioLogView.trace(sessionId);
        int turnsToKeep = Math.max(1, ENTRIES_READ / 2);
        int from = Math.max(0, turns.size() - turnsToKeep);
        List<ChatActor.HistoryEntry> out = new ArrayList<>();
        for (IoLogView.TraceTurn t : turns.subList(from, turns.size())) {
            if (t.userPrompt() != null && !t.userPrompt().isBlank()) {
                out.add(new ChatActor.HistoryEntry("user", t.userPrompt()));
            }
            for (IoLogView.TraceStep step : t.steps()) {
                if ("llm".equals(step.kind()) && step.thought() != null && !step.thought().isBlank()) {
                    out.add(new ChatActor.HistoryEntry("assistant", step.thought()));
                }
            }
        }
        return out;
    }

    /** One conversation per user, each its own part. */
    private ActivityAnswer perUser() {
        List<String> userIds = new ArrayList<>(actorSystem.getMultiUserExtension().getUserIds());
        userIds.sort(java.util.Comparator.naturalOrder());

        List<Map<String, String>> parts = new ArrayList<>();
        for (String userId : userIds) {
            List<ChatActor.HistoryEntry> entries =
                    actorSystem.getMultiUserExtension().getHistory(userId, ENTRIES_READ);
            if (entries.isEmpty()) continue;
            String subject = summarizer.summarise(material(entries));
            if (subject == null) continue;
            Map<String, String> part = new LinkedHashMap<>();
            part.put("name", userId);
            part.put("summary", subject);
            parts.add(part);
        }

        String summary;
        if (parts.isEmpty()) {
            summary = userIds.isEmpty() ? "No conversation yet."
                                        : "There are conversations for " + userIds.size()
                                          + " users, but they could not be summarised.";
        } else if (parts.size() == 1) {
            summary = parts.get(0).get("summary");
        } else {
            summary = parts.get(0).get("summary") + " and " + (parts.size() - 1) + " more.";
        }
        return new ActivityAnswer(summary, Instant.now(), List.copyOf(parts), !parts.isEmpty());
    }

    /**
     * The conversation as the material a model is given.
     *
     * @param entries the conversation, oldest first
     * @return one line per entry, each labelled with who said it
     */
    static String material(List<ChatActor.HistoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (ChatActor.HistoryEntry e : entries) {
            sb.append("user".equals(e.role()) ? "Q: " : "A: ").append(clip(e.content())).append("\n");
        }
        return sb.toString();
    }

    /** Keeps one entry short: a subject is drawn from what was asked, not from the whole answer. */
    static String clip(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= CHARS_PER_ENTRY ? one : one.substring(0, CHARS_PER_ENTRY) + "…";
    }
}
