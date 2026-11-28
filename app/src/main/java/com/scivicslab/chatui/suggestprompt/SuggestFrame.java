package com.scivicslab.chatui.suggestprompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * One generation of the work frame of one ConversationSquad actor: the topics, steps and terms recorded from the
 * conversation. Immutable; {@link #apply} and {@link #nextEpoch} return new frames. The vocabularies are closed, and
 * anything outside them, outside the length limits or outside the keys is discarded, never truncated or repaired.
 *
 * @param squad         the qualified name of the ConversationSquad actor
 * @param epoch         the generation; one more after every {@code /clear}
 * @param role          {@link #SUPERIOR}, {@link #SUBORDINATE} or {@link #STANDALONE}; decided by code
 * @param overview      one to two sentences; empty when none is recorded
 * @param digestedTurn  the last turn of the conversation read into this frame
 * @param revision      increases with every apply
 * @param updatedAt     milliseconds since the epoch of the last apply; 0 when never applied
 * @param topics        in {@code topicId} order
 */
public record SuggestFrame(String squad, int epoch, String role, String overview, int digestedTurn, int revision,
                           long updatedAt, List<Topic> topics) {

    public static final String SUPERIOR = "superior";
    public static final String SUBORDINATE = "subordinate";
    public static final String STANDALONE = "standalone";
    public static final Set<String> ROLES = Set.of(SUPERIOR, SUBORDINATE, STANDALONE);

    /** The {@code work_type} of a question every work type gets. */
    public static final String COMMON = "common";
    public static final Set<String> WORK_TYPES =
            Set.of("investigate", "design", "implement", "verify", "operate", "write");

    public static final Set<String> STATUSES = Set.of("open", "done", "dropped");
    public static final Set<String> PROGRESSES = Set.of("not-started", "in-progress", "blocked", "done");

    public static final int MAX_OVERVIEW = 300;
    public static final int MAX_TITLE = 80;
    public static final int MAX_SUMMARY = 300;
    public static final int MAX_NOTE = 200;
    public static final int MAX_TERM = 40;
    public static final int MAX_OPEN_TOPICS = 12;
    public static final int MAX_STEPS_PER_TOPIC = 12;
    public static final int MAX_TERMS_PER_TOPIC = 20;

    public record Topic(int topicId, String title, String summary, String workType, String delegateSquad,
                        String status, int lastTurn, List<Step> steps, List<Term> terms) {
        public Topic {
            steps = List.copyOf(steps);
            terms = List.copyOf(terms);
        }
    }

    public record Step(int seq, String title, String progress, String note) {
    }

    /** @param turn the turn the term first came up in */
    public record Term(String term, int turn) {
    }

    /** One element of a patch that was not applied, and why. */
    public record Rejected(String element, String reason) {
    }

    /** The new frame, and the elements of the patch that were discarded. */
    public record Applied(SuggestFrame frame, List<Rejected> rejected) {
    }

    public SuggestFrame {
        topics = List.copyOf(topics);
    }

    /** An empty frame: no topics, revision 0, nothing read yet. */
    public static SuggestFrame empty(String squad, int epoch, String role) {
        return new SuggestFrame(squad, epoch, role, "", 0, 0, 0L, List.of());
    }

    /** @return the frame of the next generation: {@code epoch} one more, empty, revision and digested turn 0 */
    public SuggestFrame nextEpoch(String newRole) {
        return empty(squad, epoch + 1, newRole);
    }

    /** @return whether nothing is recorded: no overview and no topic */
    public boolean isEmpty() {
        return overview.isEmpty() && topics.isEmpty();
    }

    /**
     * Applies a patch. Each element is checked alone; one that fails is left out and reported, the rest are applied.
     * {@code revision} goes up by one and {@code updatedAt} becomes {@code now} even if every element was left out.
     * {@code lastTurn} becomes {@code turn} for the topics the patch changed. {@code digestedTurn} is not moved.
     * {@code role} and {@code delegateSquad} are not in a patch and stay as they are.
     */
    public Applied apply(SuggestPatch patch, int turn, long now) {
        SuggestPatch p = patch == null ? SuggestPatch.empty() : patch;
        List<Rejected> rejected = new ArrayList<>();
        List<Topic> work = new ArrayList<>(topics);
        String newOverview = overview;
        Set<Integer> touched = new java.util.HashSet<>();

        if (p.overview() != null) {
            String why = textProblem(p.overview().strip(), MAX_OVERVIEW, true);
            if (why != null) rejected.add(new Rejected("overview", why));
            else newOverview = p.overview().strip();
        }

        int index = 0;
        for (SuggestPatch.TopicPatch t : p.topics()) {
            String name = "topics[" + index++ + "]";
            String why = t.topicId() == null ? addTopic(work, t, turn, touched) : updateTopic(work, t, touched);
            if (why != null) rejected.add(new Rejected(name, why));
        }
        index = 0;
        for (SuggestPatch.StepPatch s : p.steps()) {
            String name = "steps[" + index++ + "]";
            String why = upsertStep(work, s, touched);
            if (why != null) rejected.add(new Rejected(name, why));
        }
        index = 0;
        for (SuggestPatch.TermPatch t : p.terms()) {
            String name = "terms[" + index++ + "]";
            String why = addTerm(work, t, turn, touched);
            if (why != null) rejected.add(new Rejected(name, why));
        }

        List<Topic> out = new ArrayList<>();
        for (Topic t : work) {
            out.add(touched.contains(t.topicId())
                    ? new Topic(t.topicId(), t.title(), t.summary(), t.workType(), t.delegateSquad(), t.status(),
                            turn, t.steps(), t.terms())
                    : t);
        }
        out.sort(Comparator.comparingInt(Topic::topicId));
        return new Applied(new SuggestFrame(squad, epoch, role, newOverview, digestedTurn, revision + 1, now, out),
                List.copyOf(rejected));
    }

    private static String addTopic(List<Topic> work, SuggestPatch.TopicPatch t, int turn, Set<Integer> touched) {
        String title = strip(t.title());
        String why = textProblem(title, MAX_TITLE, false);
        if (why != null) return "a new topic needs a title: " + why;
        if (t.workType() == null || !WORK_TYPES.contains(t.workType())) {
            return "a new topic needs a work_type of the closed vocabulary, got " + quote(t.workType());
        }
        String summary = t.summary() == null ? "" : t.summary().strip();
        why = textProblem(summary, MAX_SUMMARY, true);
        if (why != null) return "summary: " + why;
        String status = t.status() == null ? "open" : t.status();
        if (!STATUSES.contains(status)) return "status is not in the vocabulary: " + quote(t.status());
        if (status.equals("open") && openCount(work) >= MAX_OPEN_TOPICS) {
            return "there are already " + MAX_OPEN_TOPICS + " open topics";
        }
        int id = work.stream().mapToInt(Topic::topicId).max().orElse(0) + 1;
        work.add(new Topic(id, title, summary, t.workType(), null, status, turn, List.of(), List.of()));
        touched.add(id);
        return null;
    }

    private static String updateTopic(List<Topic> work, SuggestPatch.TopicPatch t, Set<Integer> touched) {
        int at = indexOf(work, t.topicId());
        if (at < 0) return "no topic with topic_id " + t.topicId();
        Topic old = work.get(at);
        String title = old.title();
        if (t.title() != null) {
            title = t.title().strip();
            String why = textProblem(title, MAX_TITLE, false);
            if (why != null) return "title: " + why;
        }
        String summary = old.summary();
        if (t.summary() != null) {
            summary = t.summary().strip();
            String why = textProblem(summary, MAX_SUMMARY, true);
            if (why != null) return "summary: " + why;
        }
        String workType = old.workType();
        if (t.workType() != null) {
            if (!WORK_TYPES.contains(t.workType())) return "work_type is not in the vocabulary: " + quote(t.workType());
            workType = t.workType();
        }
        String status = old.status();
        if (t.status() != null) {
            if (!STATUSES.contains(t.status())) return "status is not in the vocabulary: " + quote(t.status());
            if (t.status().equals("open") && !old.status().equals("open") && openCount(work) >= MAX_OPEN_TOPICS) {
                return "there are already " + MAX_OPEN_TOPICS + " open topics";
            }
            status = t.status();
        }
        work.set(at, new Topic(old.topicId(), title, summary, workType, old.delegateSquad(), status, old.lastTurn(),
                old.steps(), old.terms()));
        touched.add(old.topicId());
        return null;
    }

    private static String upsertStep(List<Topic> work, SuggestPatch.StepPatch s, Set<Integer> touched) {
        if (s.topicId() == null) return "a step needs a topic_id";
        int at = indexOf(work, s.topicId());
        if (at < 0) return "no topic with topic_id " + s.topicId();
        Topic topic = work.get(at);
        String note = s.note() == null ? null : s.note().strip();
        if (note != null) {
            String why = textProblem(note, MAX_NOTE, true);
            if (why != null) return "note: " + why;
        }
        if (s.progress() != null && !PROGRESSES.contains(s.progress())) {
            return "progress is not in the vocabulary: " + quote(s.progress());
        }
        List<Step> steps = new ArrayList<>(topic.steps());
        if (s.seq() == null) {
            String title = strip(s.title());
            String why = textProblem(title, MAX_TITLE, false);
            if (why != null) return "a new step needs a title: " + why;
            if (steps.size() >= MAX_STEPS_PER_TOPIC) return "the topic already has " + MAX_STEPS_PER_TOPIC + " steps";
            int seq = steps.stream().mapToInt(Step::seq).max().orElse(0) + 1;
            steps.add(new Step(seq, title, s.progress() == null ? "not-started" : s.progress(),
                    note == null ? "" : note));
        } else {
            int k = -1;
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).seq() == s.seq()) k = i;
            if (k < 0) return "topic " + s.topicId() + " has no step with seq " + s.seq();
            Step old = steps.get(k);
            String title = old.title();
            if (s.title() != null) {
                title = s.title().strip();
                String why = textProblem(title, MAX_TITLE, false);
                if (why != null) return "title: " + why;
            }
            steps.set(k, new Step(old.seq(), title, s.progress() == null ? old.progress() : s.progress(),
                    note == null ? old.note() : note));
        }
        work.set(at, new Topic(topic.topicId(), topic.title(), topic.summary(), topic.workType(),
                topic.delegateSquad(), topic.status(), topic.lastTurn(), steps, topic.terms()));
        touched.add(topic.topicId());
        return null;
    }

    private static String addTerm(List<Topic> work, SuggestPatch.TermPatch t, int turn, Set<Integer> touched) {
        if (t.topicId() == null) return "a term needs a topic_id";
        int at = indexOf(work, t.topicId());
        if (at < 0) return "no topic with topic_id " + t.topicId();
        Topic topic = work.get(at);
        String term = strip(t.term());
        String why = textProblem(term, MAX_TERM, false);
        if (why != null) return "term: " + why;
        for (Term existing : topic.terms()) {
            if (existing.term().equals(term)) return "the term is already recorded for the topic";
        }
        if (topic.terms().size() >= MAX_TERMS_PER_TOPIC) return "the topic already has " + MAX_TERMS_PER_TOPIC + " terms";
        List<Term> terms = new ArrayList<>(topic.terms());
        terms.add(new Term(term, turn));
        work.set(at, new Topic(topic.topicId(), topic.title(), topic.summary(), topic.workType(),
                topic.delegateSquad(), topic.status(), topic.lastTurn(), topic.steps(), terms));
        touched.add(topic.topicId());
        return null;
    }

    private static int indexOf(List<Topic> work, int topicId) {
        for (int i = 0; i < work.size(); i++) if (work.get(i).topicId() == topicId) return i;
        return -1;
    }

    private static int openCount(List<Topic> work) {
        int n = 0;
        for (Topic t : work) if (t.status().equals("open")) n++;
        return n;
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }

    private static String quote(String s) {
        return s == null ? "nothing" : "\"" + s + "\"";
    }

    /** @return why the text is not allowed, or {@code null}; length is in characters, not truncated */
    private static String textProblem(String s, int max, boolean mayBeEmpty) {
        if (s == null) return "missing";
        if (s.isEmpty() && !mayBeEmpty) return "empty";
        int n = s.codePointCount(0, s.length());
        return n > max ? "longer than " + max + " characters (" + n + ")" : null;
    }
}
