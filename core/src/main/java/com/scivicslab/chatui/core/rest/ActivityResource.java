package com.scivicslab.chatui.core.rest;

import com.scivicslab.chatui.core.activity.ActivitySummarizer;
import com.scivicslab.chatui.core.actor.ChatActor;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers what this instance is working on ({@code ActivitySummary_260905_oo01}).
 *
 * <p>The unit here is the conversation, not the project: this product has one
 * {@link ChatActor}, so the answer is that conversation's subject and there is no "and N others" to
 * add. Started with {@code MultiUserExtension}, each user has their own conversation, and those
 * become the parts.</p>
 *
 * <p>A conversation has no title, so the subject is read out of it by a model
 * ({@link ActivitySummarizer}).</p>
 */
@Path("/api/activity")
@Produces(MediaType.APPLICATION_JSON)
public class ActivityResource {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ActivityResource.class.getName());

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    ActivitySummarizer summarizer;

    /**
     * How long an answer stands before it is worked out again.
     *
     * <p>One call to a model per conversation, drawn on a screen that lists every running tool.
     * Which piece of work a conversation is on does not turn over in minutes.</p>
     */
    private static final Duration MAX_AGE = Duration.ofMinutes(30);

    /**
     * How long an answer that no model produced stands.
     *
     * <p>Much shorter, because these are the two answers that are about to stop being true. An
     * instance is asked as soon as it is READY, before anyone has said anything to it, and the
     * answer worked out then — "no conversation yet" — would otherwise be repeated for half an hour
     * after the work began. The same goes for a failure: the broker being unreachable now says
     * nothing about the next half hour. Neither costs a model call to work out again.</p>
     */
    private static final Duration RETRY_AGE = Duration.ofMinutes(1);

    /** How many of a conversation's most recent entries are read. */
    private static final int ENTRIES_READ = 24;

    /** How much of one entry is passed on. A subject does not need whole answers. */
    private static final int CHARS_PER_ENTRY = 400;

    private volatile Answer cached;

    /** Whether an answer is being worked out, so that ten requests do not start ten of them. */
    private final java.util.concurrent.atomic.AtomicBoolean working =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * One worked-out answer and the moment it was worked out.
     *
     * @param fromModel whether a model produced it, which decides how long it stands
     */
    private record Answer(String summary, Instant asOf, List<Map<String, String>> parts,
                          boolean fromModel) {}

    /**
     * Returns what this instance is working on.
     *
     * @return {@code {summary, asOf, parts}}
     */
    @GET
    public Map<String, Object> activity() {
        Answer answer = cached;
        // The answer that is held is returned as it stands, and a stale one is renewed behind this
        // request rather than in front of it. Whoever asks is drawing a dashboard and gives up after
        // a second; working out an answer takes a call to a model. Blocking here made the column go
        // blank for one poll every half hour, exactly when the answer was being renewed.
        if (isStale(answer)) renew();
        return answerOf(answer == null ? new Answer("", Instant.now(), List.of(), false) : answer);
    }

    /** @return whether {@code answer} is missing or has stood longer than it may */
    private boolean isStale(Answer answer) {
        return answer == null || Duration.between(answer.asOf(), Instant.now())
                .compareTo(answer.fromModel() ? MAX_AGE : RETRY_AGE) > 0;
    }

    /** Works out a new answer on its own thread, unless one is already being worked out. */
    private void renew() {
        if (!working.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try {
                cached = work();
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.FINE,
                        "Could not work out what this instance is doing", e);
            } finally {
                working.set(false);
            }
        });
    }

    /** @param answer the answer to send @return it, as the three fields of the reply */
    private static Map<String, Object> answerOf(Answer answer) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", answer.summary());
        out.put("asOf", answer.asOf().toString());
        out.put("parts", answer.parts());
        return out;
    }

    /** Reads whatever conversations this instance holds and asks what each one is about. */
    private Answer work() {
        return actorSystem.isMultiUser() ? perUser() : single();
    }

    /** The one conversation this instance holds. */
    private Answer single() {
        List<ChatActor.HistoryEntry> entries =
                actorSystem.getChatActor().ask(a -> a.getHistory(ENTRIES_READ)).join();
        if (entries.isEmpty()) {
            return new Answer("まだ会話が始まっていない。", Instant.now(), List.of(), false);
        }
        String subject = summarizer.summarise(material(entries));
        return new Answer(subject == null ? "会話はあるが、要約できなかった。" : subject,
                          Instant.now(), List.of(), subject != null);
    }

    /** One conversation per user, each its own part. */
    private Answer perUser() {
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
            summary = userIds.isEmpty() ? "まだ会話が始まっていない。"
                                        : "会話は" + userIds.size() + "人分あるが、要約できなかった。";
        } else if (parts.size() == 1) {
            summary = parts.get(0).get("summary");
        } else {
            summary = parts.get(0).get("summary") + "ほかに" + (parts.size() - 1) + "人。";
        }
        return new Answer(summary, Instant.now(), List.copyOf(parts), !parts.isEmpty());
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
