package com.scivicslab.chatui.suggestprompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The "Suggest prompt" button's plain steps, which touch neither a model nor a conversation and are
 * tested alone. Predicting is two questions to a local LLM. The first reads the assistant's last
 * message and says what it asks the person, and which choices (A, B, C...) it offers, if any. The
 * second, given the choice the person made, predicts four to six long follow-up instructions.
 */
public final class PromptSuggestion {

    /** Fewest follow-up prompts worth showing; a reply with fewer readable ones is an error. */
    public static final int MIN_COUNT = 4;

    /** Most follow-up prompts shown. */
    public static final int MAX_COUNT = 6;

    /** How many of the newest messages the follow-up question is shown, for the person's way of writing. */
    public static final int RECENT_MESSAGES = 6;

    /** The most choices of one question that are offered. */
    static final int MAX_CHOICES = 8;

    /** A message longer than this is cut, keeping its end, which is where the open question usually is. */
    static final int MAX_MESSAGE_CHARS = 3000;

    /** A prompt shorter than this is not the long, specific instruction wanted. */
    static final int MIN_PROMPT_CHARS = 24;

    /** A prompt longer than this is not a prompt to put in an input box; it is dropped. */
    static final int MAX_PROMPT_CHARS = 600;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A reply of yes or no and nothing else, in English or Japanese, with the punctuation round it. */
    private static final Pattern YES_NO_ONLY = Pattern.compile(
            "^(yes|no|yep|nope|ok|okay|sure|please do|go ahead|y|n|はい|いいえ|うん|ううん|お願いします|お願い|了解|了解です|大丈夫です|そうして|そうしてください|それでお願いします)"
                    + "[\\s.!?。、,！？]*$",
            Pattern.CASE_INSENSITIVE);

    private PromptSuggestion() {}

    /**
     * One message of the conversation.
     *
     * @param fromPerson {@code true} for what the person wrote, {@code false} for the assistant's answer
     * @param text       the message
     */
    public record Message(boolean fromPerson, String text) {}

    /**
     * One choice an assistant's message offers.
     *
     * @param id   the label the message gives it, e.g. {@code A}
     * @param text what it says
     */
    public record Choice(String id, String text) {}

    /**
     * What the assistant's last message asks the person.
     *
     * @param question what it asks, in a sentence
     * @param choices  the choices it offers; empty when the question has none
     */
    public record Question(String question, List<Choice> choices) {
        public Question {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    /** @return the text of the newest assistant message, or {@code null} when there is none */
    public static String lastAssistantText(List<Message> recent) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (!recent.get(i).fromPerson()) return recent.get(i).text();
        }
        return null;
    }

    /**
     * @param assistantText the assistant's last message
     * @return the first question to the model: what is asked, and which choices are offered
     */
    public static String questionPromptFor(String assistantText) {
        return "Below is the last message an AI assistant wrote to a person.\n"
                + "Read what it asks the person to decide or answer. If it offers labelled choices (A, B, C, 1, 2, 3...), list them.\n"
                + "Reply with ONLY a JSON object, no preamble and no code fence, in this form:\n"
                + "{\"question\": \"what the assistant asks, in one sentence\", "
                + "\"choices\": [{\"id\": \"A\", \"text\": \"what choice A is\"}]}\n"
                + "Use an empty \"choices\" array when the message offers no choices. Do not invent choices.\n\n"
                + "Message:\n" + clip(assistantText) + "\n";
    }

    /**
     * Reads the model's reply to {@link #questionPromptFor}, tolerating a code fence or a sentence
     * around the object. Fewer than two choices are no choices.
     *
     * @return the question, or empty when no object with a question can be read
     */
    public static Optional<Question> parseQuestion(String reply) {
        JsonNode obj = readJson(reply, '{', '}');
        if (obj == null || !obj.isObject()) return Optional.empty();
        String question = obj.path("question").isTextual() ? obj.get("question").asText().strip() : "";
        if (question.isEmpty()) return Optional.empty();
        List<Choice> choices = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        JsonNode array = obj.path("choices");
        if (array.isArray()) {
            for (JsonNode c : array) {
                String id;
                String text;
                if (c.isObject()) {
                    id = c.path("id").asText("").strip();
                    text = c.path("text").asText("").strip();
                } else if (c.isTextual()) {
                    id = String.valueOf((char) ('A' + choices.size()));
                    text = c.asText().strip();
                } else {
                    continue;
                }
                if (id.isEmpty() || text.isEmpty() || !ids.add(id)) continue;
                choices.add(new Choice(id, text));
                if (choices.size() == MAX_CHOICES) break;
            }
        }
        return Optional.of(new Question(question, choices.size() >= 2 ? choices : List.of()));
    }

    /**
     * @param recent the conversation's newest messages, oldest first; the last {@link #RECENT_MESSAGES}
     *               are shown, and the newest assistant message is the one answered
     * @param asked  what that message asks
     * @param chosen the choice the person made, or {@code null} when the question had none
     * @return the second question to the model: the follow-up instructions the person may give
     */
    public static String followupPromptFor(List<Message> recent, Question asked, Choice chosen) {
        int from = Math.max(0, recent.size() - RECENT_MESSAGES);
        StringBuilder sb = new StringBuilder();
        sb.append("Below is the end of a conversation between a person and an AI assistant.\n")
          .append("The assistant's last message asks: ").append(asked.question()).append("\n");
        if (chosen != null) {
            sb.append("The person has decided: ").append(chosen.id()).append(" — ").append(chosen.text()).append("\n");
        }
        sb.append("Predict ").append(MIN_COUNT).append(" to ").append(MAX_COUNT)
          .append(" different instructions the person is likely to give next")
          .append(chosen != null ? ", taking that decision as made.\n" : ".\n")
          .append("Each instruction must be concrete and fairly long (two or three sentences): say what to do and include a reason, a constraint, ")
          .append("or the form the result should take. Never a bare yes or no, and do not repeat the decision itself.\n")
          .append("Write each in the person's own voice and in the language the person has been using.\n")
          .append("Reply with ONLY a JSON array of strings — no preamble, no explanation, no code fence.\n\n")
          .append("Conversation:\n");
        for (Message m : recent.subList(from, recent.size())) {
            sb.append(m.fromPerson() ? "PERSON: " : "ASSISTANT: ").append(clip(m.text())).append("\n\n");
        }
        return sb.toString();
    }

    private static String clip(String text) {
        String t = text == null ? "" : text.strip();
        return t.length() <= MAX_MESSAGE_CHARS ? t : "…" + t.substring(t.length() - MAX_MESSAGE_CHARS);
    }

    /**
     * Reads the model's reply to {@link #followupPromptFor} as a JSON array of strings, tolerating a
     * code fence or a sentence around it.
     *
     * @return the prompts, stripped, without duplicates, bare yes/no answers, too-short or too-long
     *         ones, at most {@link #MAX_COUNT}; empty when no array of strings can be read. The caller
     *         decides whether fewer than {@link #MIN_COUNT} are enough.
     */
    public static List<String> parseFollowups(String reply) {
        JsonNode array = readJson(reply, '[', ']');
        if (array == null || !array.isArray()) return List.of();
        Set<String> prompts = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) continue;
            String s = item.asText().strip();
            if (s.length() < MIN_PROMPT_CHARS || s.length() > MAX_PROMPT_CHARS || isYesNoOnly(s)) continue;
            prompts.add(s);
            if (prompts.size() == MAX_COUNT) break;
        }
        return new ArrayList<>(prompts);
    }

    /** @return whether {@code text} is a yes or no, an acknowledgement or a plain "please do", and nothing more */
    public static boolean isYesNoOnly(String text) {
        return text == null || YES_NO_ONLY.matcher(text.strip()).matches();
    }

    /**
     * What a choice puts at the head of the prompt chosen after it.
     *
     * @return e.g. {@code Go with A. }
     */
    public static String headFor(Choice chosen) {
        return chosen == null ? "" : "Go with " + chosen.id() + ". ";
    }

    private static JsonNode readJson(String reply, char open, char close) {
        if (reply == null) return null;
        int from = reply.indexOf(open);
        int to = reply.lastIndexOf(close);
        if (from < 0 || to <= from) return null;
        try {
            return JSON.readTree(reply.substring(from, to + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
