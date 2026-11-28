package com.scivicslab.chatui.suggestprompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Test doubles for {@link SuggestPromptKeeper}: the repository, the turns, the model, and a hand-driven mailbox. */
public final class SuggestKeeperDoubles {

    private SuggestKeeperDoubles() {
    }

    /** A patch that adds one topic; the keeper's frame is not empty afterwards. */
    static final String REPLY = "{\"overview\":\"We rebuild it.\",\"topics\":[{\"title\":\"A topic\",\"work_type\":\"verify\"}]}";

    /** In memory; counts and keeps every save. */
    public static final class Repo implements SuggestRepository {
        public final Map<String, TreeMap<Integer, SuggestFrame>> frames = new TreeMap<>();
        public final List<SuggestFrame> saves = new ArrayList<>();
        public volatile List<SuggestQuestion> questions = List.of();

        @Override
        public Optional<SuggestFrame> loadCurrent(String squad) {
            TreeMap<Integer, SuggestFrame> m = frames.get(squad);
            return m == null || m.isEmpty() ? Optional.empty() : Optional.of(m.lastEntry().getValue());
        }

        @Override
        public int maxEpoch(String squad) {
            TreeMap<Integer, SuggestFrame> m = frames.get(squad);
            return m == null || m.isEmpty() ? 0 : m.lastKey();
        }

        @Override
        public void save(SuggestFrame frame) {
            frames.computeIfAbsent(frame.squad(), k -> new TreeMap<>()).put(frame.epoch(), frame);
            saves.add(frame);
        }

        @Override
        public List<SuggestQuestion> questions() {
            return questions;
        }

        @Override
        public void replaceQuestions(List<SuggestQuestion> questions) {
            this.questions = List.copyOf(questions);
        }
    }

    /** The finished turns, as a test sets them; remembers what range each read asked for. */
    static final class FakeTurns implements SuggestPromptKeeper.Turns {
        int finished;
        final List<SuggestPatchPrompt.Message> rows = new ArrayList<>();
        final List<int[]> reads = new ArrayList<>();
        RuntimeException readFails;

        /** Sets the finished turn and gives each turn up to it one short question and one short answer. */
        FakeTurns finish(int upTo) {
            for (int t = rows.isEmpty() ? 1 : rows.get(rows.size() - 1).turn() + 1; t <= upTo; t++) {
                rows.add(new SuggestPatchPrompt.Message(t, true, "question " + t));
                rows.add(new SuggestPatchPrompt.Message(t, false, "answer " + t));
            }
            finished = upTo;
            return this;
        }

        @Override
        public int finishedTurn() {
            return finished;
        }

        @Override
        public List<SuggestPatchPrompt.Message> read(int afterTurn, int upToTurn) {
            reads.add(new int[] {afterTurn, upToTurn});
            if (readFails != null) throw readFails;
            return rows.stream().filter(m -> m.turn() > afterTurn && m.turn() <= upToTurn)
                    .sorted(Comparator.comparingInt(SuggestPatchPrompt.Message::turn)).toList();
        }
    }

    /** Both the model and the launcher: a call waits until the test runs it, and then answers or fails as scripted. */
    static final class HandDrivenModel implements SuggestPromptKeeper.Model, SuggestPromptKeeper.Launcher {
        final List<String> prompts = new ArrayList<>();
        final Deque<Runnable> waiting = new ArrayDeque<>();
        final Deque<Object> script = new ArrayDeque<>();

        /** Queues what the next call does: a reply text, or an exception to throw. */
        HandDrivenModel then(Object replyOrFailure) {
            script.add(replyOrFailure);
            return this;
        }

        @Override
        public String complete(String prompt) throws Exception {
            prompts.add(prompt);
            Object next = script.isEmpty() ? REPLY : script.poll();
            if (next instanceof Exception e) throw e;
            return (String) next;
        }

        @Override
        public void launch(Runnable blockingCall) {
            waiting.add(blockingCall);
        }

        /** Lets the oldest waiting call run to its end. */
        void finishOneCall() {
            waiting.poll().run();
        }
    }

    /** The keeper's own mailbox, drained by hand: what the actor's FIFO queue is in a test. */
    static final class Mailbox {
        final Deque<Consumer<SuggestPromptKeeper>> messages = new ArrayDeque<>();
        SuggestPromptKeeper keeper;

        void bind(SuggestPromptKeeper k) {
            keeper = k;
            k.bind(messages::add);
        }

        /** Runs the oldest message only. */
        void step() {
            messages.poll().accept(keeper);
        }

        /** Runs every message, including the ones they send, in order. */
        void drain() {
            while (!messages.isEmpty()) messages.poll().accept(keeper);
        }
    }
}
