package com.scivicslab.chatui.suggestprompt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The text asked of the local LLM to get a {@link SuggestPatch}, and the reading of its answer. Plain steps that touch
 * neither a model nor a database. The work frame and the closed vocabularies are the code's; the LLM only proposes
 * changes, and {@link SuggestFrame#apply} decides what is taken.
 */
public final class SuggestPatchPrompt {

    /** One new message is cut to this many characters. */
    public static final int MAX_MESSAGE_CHARS = 3000;

    /** All the new messages of one request together are at most this many characters, oldest first. */
    public static final int MAX_INPUT_CHARS = 12000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private SuggestPatchPrompt() {
    }

    /**
     * One message of a finished turn.
     *
     * @param turn       the turn the message belongs to, counted from 1 in the current I/O log session
     * @param fromPerson {@code true} for what the person wrote, {@code false} for the assistant's answer
     * @param text       the message
     */
    public record Message(int turn, boolean fromPerson, String text) {
    }

    /**
     * A request to the LLM.
     *
     * @param prompt       the text to send; empty when there is nothing new to read, so no call is needed
     * @param digestedUpTo the turn the frame's {@code digested_turn} may advance to once the patch is applied
     */
    public record Built(String prompt, int digestedUpTo) {
        /** @return whether the LLM has to be called */
        public boolean needsModel() {
            return !prompt.isEmpty();
        }
    }

    /**
     * The answer, read.
     *
     * @param patch   the patch; empty when the answer holds no readable JSON object
     * @param reason  why there is no patch; empty when there is one
     * @param dropped the elements of the answer that could not even be read into a patch, and why
     */
    public record Parsed(Optional<SuggestPatch> patch, String reason, List<SuggestFrame.Rejected> dropped) {
        public Parsed {
            dropped = List.copyOf(dropped);
        }
    }

    /**
     * Builds the request. Messages of turns that are not after {@code frame.digestedTurn()} or are after
     * {@code finishedTurn} are not read. Messages are taken oldest first, each cut to {@link #MAX_MESSAGE_CHARS}, until
     * {@link #MAX_INPUT_CHARS} is full. A turn is taken whole or not at all, so {@code digestedUpTo} is the last turn
     * taken whole; but the first turn is always taken, cut at the limit, so that every request moves forward.
     *
     * @param frame        the current work frame
     * @param newMessages  messages of finished turns, oldest first
     * @param finishedTurn the number of the last finished turn
     */
    public static Built build(SuggestFrame frame, List<Message> newMessages, int finishedTurn) {
        List<Message> readable = new ArrayList<>();
        for (Message m : newMessages) {
            if (m.turn() > frame.digestedTurn() && m.turn() <= finishedTurn && m.text() != null
                    && !m.text().isBlank()) {
                readable.add(m);
            }
        }
        if (readable.isEmpty()) return new Built("", Math.max(frame.digestedTurn(), finishedTurn));

        // Group by turn; a turn is taken whole or not at all, except the first, which is cut at the limit.
        StringBuilder body = new StringBuilder();
        int lastWhole = -1;
        int i = 0;
        boolean allTaken = true;
        while (i < readable.size()) {
            int turn = readable.get(i).turn();
            StringBuilder part = new StringBuilder("[turn " + turn + "]\n");
            while (i < readable.size() && readable.get(i).turn() == turn) {
                Message m = readable.get(i++);
                part.append(m.fromPerson() ? "PERSON: " : "ASSISTANT: ").append(clip(m.text())).append("\n\n");
            }
            if (body.length() + part.length() <= MAX_INPUT_CHARS) {
                body.append(part);
                lastWhole = turn;
            } else {
                if (lastWhole < 0) {
                    body.append(part, 0, MAX_INPUT_CHARS);
                    lastWhole = turn;
                }
                allTaken = false;
                break;
            }
        }
        return new Built(instructions(frame) + body, allTaken ? Math.max(finishedTurn, lastWhole) : lastWhole);
    }

    private static String instructions(SuggestFrame frame) {
        return "You keep a work frame of a conversation between a person and an AI assistant: the topics being worked on, "
                + "their steps and the terms that came up. Below are the current work frame as JSON and the new messages. "
                + "Reply with the changes the new messages make to the frame.\n\n"
                + "Reply with ONLY a JSON object, no preamble and no code fence, in this form (every key is optional):\n"
                + "{\"overview\": \"1-2 sentences\",\n"
                + " \"topics\": [{\"topic_id\": 3, \"title\": \"...\", \"summary\": \"...\", \"work_type\": \"implement\", \"status\": \"open\"}],\n"
                + " \"steps\": [{\"topic_id\": 3, \"seq\": 2, \"title\": \"...\", \"progress\": \"in-progress\", \"note\": \"...\"}],\n"
                + " \"terms\": [{\"topic_id\": 3, \"term\": \"...\"}]}\n\n"
                + "Rules:\n"
                + "- Write only what changed. Anything you leave out stays as it is. If nothing changed, reply {}.\n"
                + "- A topic without topic_id is new and needs title and work_type. A topic with a topic_id of the frame "
                + "is updated; write only the fields that change.\n"
                + "- A step with the seq of an existing step updates it. A step without seq is added at the end and needs a title.\n"
                + "- Nothing is deleted. To drop a topic, set its status to \"dropped\".\n"
                + "- work_type is one of: investigate, design, implement, verify, operate, write.\n"
                + "- status is one of: open, done, dropped.\n"
                + "- progress is one of: not-started, in-progress, blocked, done.\n"
                + "- Do not write role, delegate_squad or turn numbers; they are not yours to set.\n"
                + "- Terms are words a reader may need explained; add only what came up in the new messages.\n"
                + "- Limits (anything longer is thrown away, not cut): overview " + SuggestFrame.MAX_OVERVIEW
                + " characters; title " + SuggestFrame.MAX_TITLE + "; summary " + SuggestFrame.MAX_SUMMARY
                + "; note " + SuggestFrame.MAX_NOTE + "; term " + SuggestFrame.MAX_TERM + "; at most "
                + SuggestFrame.MAX_OPEN_TOPICS + " open topics, " + SuggestFrame.MAX_STEPS_PER_TOPIC
                + " steps and " + SuggestFrame.MAX_TERMS_PER_TOPIC + " terms per topic.\n"
                + "- Write in the language the person has been using.\n\n"
                + "Current work frame:\n" + SuggestDigest.of(frame) + "\n\n"
                + "New messages:\n";
    }

    private static String clip(String text) {
        String t = text.strip();
        return t.length() <= MAX_MESSAGE_CHARS ? t : t.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    /**
     * Reads the LLM's answer, tolerating a code fence or sentences around the object. A field of the wrong type drops
     * its element, which is reported; nothing is repaired. Whether the values are in the vocabularies and limits is
     * {@link SuggestFrame#apply}'s to decide.
     */
    public static Parsed parse(String reply) {
        if (reply == null || reply.isBlank()) return new Parsed(Optional.empty(), "the answer is empty", List.of());
        JsonNode obj = firstObject(reply);
        if (obj == null) return new Parsed(Optional.empty(), "the answer holds no JSON object", List.of());

        List<SuggestFrame.Rejected> dropped = new ArrayList<>();
        String overview = null;
        JsonNode ov = obj.get("overview");
        if (ov != null && !ov.isNull()) {
            if (ov.isTextual()) overview = ov.asText();
            else dropped.add(new SuggestFrame.Rejected("overview", "not a string"));
        }
        List<SuggestPatch.TopicPatch> topics = new ArrayList<>();
        for (Element e : elements(obj, "topics", dropped)) {
            try {
                requireObject(e);
                topics.add(new SuggestPatch.TopicPatch(intOf(e.node, "topic_id"), textOf(e.node, "title"),
                        textOf(e.node, "summary"), textOf(e.node, "work_type"), textOf(e.node, "status")));
            } catch (IllegalArgumentException x) {
                dropped.add(new SuggestFrame.Rejected(e.name, x.getMessage()));
            }
        }
        List<SuggestPatch.StepPatch> steps = new ArrayList<>();
        for (Element e : elements(obj, "steps", dropped)) {
            try {
                requireObject(e);
                steps.add(new SuggestPatch.StepPatch(intOf(e.node, "topic_id"), intOf(e.node, "seq"),
                        textOf(e.node, "title"), textOf(e.node, "progress"), textOf(e.node, "note")));
            } catch (IllegalArgumentException x) {
                dropped.add(new SuggestFrame.Rejected(e.name, x.getMessage()));
            }
        }
        List<SuggestPatch.TermPatch> terms = new ArrayList<>();
        for (Element e : elements(obj, "terms", dropped)) {
            try {
                requireObject(e);
                terms.add(new SuggestPatch.TermPatch(intOf(e.node, "topic_id"), textOf(e.node, "term")));
            } catch (IllegalArgumentException x) {
                dropped.add(new SuggestFrame.Rejected(e.name, x.getMessage()));
            }
        }
        return new Parsed(Optional.of(new SuggestPatch(overview, topics, steps, terms)), "", dropped);
    }

    private record Element(String name, JsonNode node) {
    }

    private static List<Element> elements(JsonNode obj, String key, List<SuggestFrame.Rejected> dropped) {
        JsonNode array = obj.get(key);
        if (array == null || array.isNull()) return List.of();
        if (!array.isArray()) {
            dropped.add(new SuggestFrame.Rejected(key, "not an array"));
            return List.of();
        }
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(new Element(key + "[" + i + "]", array.get(i)));
        }
        return out;
    }

    private static void requireObject(Element e) {
        if (!e.node.isObject()) throw new IllegalArgumentException("not an object");
    }

    private static Integer intOf(JsonNode e, String key) {
        JsonNode v = e.get(key);
        if (v == null || v.isNull()) return null;
        if (!v.isIntegralNumber() || !v.canConvertToInt()) throw new IllegalArgumentException(key + " is not an integer");
        return v.intValue();
    }

    private static String textOf(JsonNode e, String key) {
        JsonNode v = e.get(key);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw new IllegalArgumentException(key + " is not a string");
        return v.asText();
    }

    /** @return the first JSON object that can be read from the reply, trying each opening brace in turn */
    private static JsonNode firstObject(String reply) {
        int from = reply.indexOf('{');
        while (from >= 0) {
            try (JsonParser p = JSON.getFactory().createParser(reply.substring(from))) {
                JsonNode n = JSON.readTree(p);
                if (n != null && n.isObject()) return n;
            } catch (Exception ignored) {
                // not an object here; try the next brace
            }
            from = reply.indexOf('{', from + 1);
        }
        return null;
    }
}
