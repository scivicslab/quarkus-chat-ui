package com.scivicslab.chatui.suggestprompt;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps one conversation's work frame up to date ({@code SuggestPromptGuidedByWorkFrame_261128_oo01}). A plain POJO:
 * the actor around it ({@code <squad>.suggestprompt}) only gives it one message at a time, so nothing here locks.
 * Everything that waits or touches the outside world comes in through a collaborator, so tests replace them.
 *
 * <p>It has no period of its own. The conversation's {@code Timekeeper} sends {@link #bringUpToDate} at a fixed
 * interval. One update reads the finished turns after {@code digested_turn}, asks the local LLM once, applies the
 * patch, and saves. While finished turns remain unread it sends itself {@code bringUpToDate} again, without waiting for
 * the interval; after an LLM failure it does not, so the next interval retries.</p>
 *
 * <p>The LLM call waits on the actor system's managed thread pool ({@link Launcher}); its result comes back as a message
 * ({@link #onReply}, {@link #onFailure}), so the message loop is never held. Exactly one call is out at a time.</p>
 */
public class SuggestPromptKeeper {

    private static final Logger LOG = Logger.getLogger(SuggestPromptKeeper.class.getName());

    /** Where the finished turns come from. Both reads happen on the keeper's own message loop and do not wait for a turn. */
    public interface Turns {
        /** @return the number of the last finished turn of the conversation's current I/O log session */
        int finishedTurn();

        /**
         * @return the finished turns numbered above {@code afterTurn} and up to {@code upToTurn}, oldest first
         * @throws RuntimeException when the log cannot be read
         */
        List<SuggestPatchPrompt.Message> read(int afterTurn, int upToTurn);
    }

    /** The local LLM. Blocks until the reply, and gives up by itself after its own time limit. */
    public interface Model {
        /** @return the reply text; an exception means the call failed or timed out */
        String complete(String prompt) throws Exception;
    }

    /** Runs a blocking call away from the message loop, on the actor system's managed thread pool. */
    public interface Launcher {
        void launch(Runnable blockingCall);
    }

    private final String squad;
    private final SuggestRepository repository;
    private final Turns turns;
    private final Model model;
    private final Launcher launcher;
    private final Supplier<String> role;
    private final LongSupplier clock;
    private Consumer<Consumer<SuggestPromptKeeper>> self = action -> {
        throw new IllegalStateException("the keeper was not bound to its actor");
    };

    private SuggestFrame frame;
    private boolean restored;
    private boolean calling;
    private int callEpoch;

    /**
     * @param squad      the conversation's name, which keys its rows
     * @param repository where frames are kept
     * @param turns      where the finished turns are read
     * @param model      the local LLM
     * @param launcher   how the LLM call is kept off the message loop
     * @param role       the conversation's role ({@link SuggestFrame#SUPERIOR} and the others), read when a frame starts
     * @param clock      the time in milliseconds since the epoch
     */
    public SuggestPromptKeeper(String squad, SuggestRepository repository, Turns turns, Model model, Launcher launcher,
                               Supplier<String> role, LongSupplier clock) {
        this.squad = squad;
        this.repository = repository;
        this.turns = turns;
        this.model = model;
        this.launcher = launcher;
        this.role = role;
        this.clock = clock;
    }

    /**
     * Gives the keeper the way to send itself a message: {@code tell} of its own actor.
     *
     * @param self sends an action to this keeper's own message queue
     */
    public void bind(Consumer<Consumer<SuggestPromptKeeper>> self) {
        this.self = self;
    }

    /** Puts back the newest generation of this conversation's frame, as it was saved; for a conversation brought back. */
    public void restore() {
        restored = true;
        try {
            frame = repository.loadCurrent(squad).orElseGet(() -> SuggestFrame.empty(squad, Math.max(1, repository.maxEpoch(squad)), roleNow()));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, squad + ": the work frame could not be loaded", e);
        }
    }

    /**
     * Starts a generation for a conversation that was not brought back from its log, that is, a new one. A recreated
     * conversation with the name of a deleted one gets the epoch after the old rows. Does nothing after {@link #restore}.
     */
    public void startIfNew() {
        if (!restored) startNewFrame();
    }

    /**
     * Starts the next generation: the epoch goes up, the frame is empty, and the old rows stay. Called at {@code /clear},
     * when the conversation's turn numbers start again at 1. An answer of the LLM still being awaited belongs to the
     * generation before and is dropped when it arrives.
     */
    public void startNewFrame() {
        try {
            frame = SuggestFrame.empty(squad, repository.maxEpoch(squad) + 1, roleNow());
            repository.save(frame);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, squad + ": a new work frame could not be started", e);
        }
    }

    /**
     * One update. Does nothing while a call is out, or when every finished turn has been read. Only finished turns are
     * read: a turn that is running is left for later.
     */
    public void bringUpToDate() {
        if (calling) return;
        try {
            if (frame == null) restore();
            if (frame == null) return;
            int finished = turns.finishedTurn();
            if (finished <= frame.digestedTurn()) return;
            List<SuggestPatchPrompt.Message> messages = turns.read(frame.digestedTurn(), finished);
            SuggestPatchPrompt.Built built = SuggestPatchPrompt.build(frame, messages, finished);
            if (!built.needsModel()) {
                // Only cancelled or failed turns, or turns without a reply: nothing to learn, so only the mark moves.
                advanceTo(built.digestedUpTo());
                return;
            }
            calling = true;
            callEpoch = frame.epoch();
            launcher.launch(() -> call(built, callEpoch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, squad + ": the work frame could not be brought up to date", e);
        }
    }

    /** What an {@link #analyzeNow} request found; {@code turns} is the number of finished turns still to be read. */
    public record Analysis(Kind kind, int turns) {
        public enum Kind { STARTED, ALREADY_RUNNING, NOTHING_NEW }
    }

    /**
     * The person pressed {@code Analyze conversation}: one update without waiting for the interval, under the same
     * rules as the interval's (nothing while a call is out, nothing when no turn has finished since
     * {@code digested_turn}). It answers at once and does not wait for the LLM.
     */
    public Analysis analyzeNow() {
        if (calling) return new Analysis(Analysis.Kind.ALREADY_RUNNING, 0);
        try {
            if (frame == null) restore();
            if (frame == null) return new Analysis(Analysis.Kind.NOTHING_NEW, 0);
            int unread = turns.finishedTurn() - frame.digestedTurn();
            if (unread <= 0) return new Analysis(Analysis.Kind.NOTHING_NEW, 0);
            bringUpToDate();
            return new Analysis(Analysis.Kind.STARTED, unread);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, squad + ": the update asked for could not be started", e);
            return new Analysis(Analysis.Kind.NOTHING_NEW, 0);
        }
    }

    /** Runs on the launcher's thread: waits for the LLM, and hands the result back as a message. */
    private void call(SuggestPatchPrompt.Built built, int epoch) {
        String reply;
        try {
            reply = model.complete(built.prompt());
        } catch (Exception e) {
            self.accept(k -> k.onFailure(e));
            return;
        }
        self.accept(k -> k.onReply(built, epoch, reply));
    }

    /** The LLM answered. Applies the patch, moves {@code digested_turn}, saves, and goes on if turns remain. */
    public void onReply(SuggestPatchPrompt.Built built, int epoch, String reply) {
        calling = false;
        if (frame == null || frame.epoch() != epoch) return;   // a /clear came in between: the answer is for the old frame
        try {
            SuggestPatchPrompt.Parsed parsed = SuggestPatchPrompt.parse(reply);
            Optional<SuggestPatch> patch = parsed.patch();
            if (patch.isEmpty()) {
                // Nothing readable: the turns stay unread, and the next interval asks again.
                LOG.warning(squad + ": the LLM's reply held no patch: " + parsed.reason());
                return;
            }
            SuggestFrame.Applied applied = frame.apply(patch.get(), built.digestedUpTo(), clock.getAsLong());
            for (SuggestFrame.Rejected r : applied.rejected()) {
                LOG.warning(squad + ": patch element left out: " + r.element() + ": " + r.reason());
            }
            for (SuggestFrame.Rejected r : parsed.dropped()) {
                LOG.warning(squad + ": patch element unreadable: " + r.element() + ": " + r.reason());
            }
            SuggestFrame f = applied.frame();
            frame = new SuggestFrame(f.squad(), f.epoch(), f.role(), f.overview(), built.digestedUpTo(), f.revision(),
                    f.updatedAt(), f.topics());
            repository.save(frame);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, squad + ": the patch could not be applied", e);
            return;
        }
        continueIfBehind();
    }

    /** The LLM call failed or timed out. {@code digested_turn} does not move, and nothing is read until the next interval. */
    public void onFailure(Exception cause) {
        calling = false;
        LOG.log(Level.WARNING, squad + ": the LLM call for the work frame failed", cause);
    }

    /** Moves {@code digested_turn} without a patch, saves, and goes on if turns remain. */
    private void advanceTo(int turn) {
        if (turn <= frame.digestedTurn()) return;
        frame = new SuggestFrame(frame.squad(), frame.epoch(), frame.role(), frame.overview(), turn, frame.revision(),
                frame.updatedAt(), frame.topics());
        repository.save(frame);
        continueIfBehind();
    }

    /** Catch-up: while finished turns are still unread, update again at once instead of waiting for the interval. */
    private void continueIfBehind() {
        if (turns.finishedTurn() > frame.digestedTurn()) self.accept(SuggestPromptKeeper::bringUpToDate);
    }

    private String roleNow() {
        String r = role.get();
        return r == null ? SuggestFrame.STANDALONE : r;
    }

    /** @return the frame in hand, or {@code null} before one was loaded or started; for tests */
    public SuggestFrame frame() {
        return frame;
    }

    /** @return whether an LLM call is out; for tests */
    public boolean calling() {
        return calling;
    }
}
