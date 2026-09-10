package com.scivicslab.chatui.core.activity;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds what this instance is doing, and works out a new answer when the held one has stood long
 * enough ({@code ActivitySummary_260905_oo01}).
 *
 * <p>A plain object with no knowledge of threads, held by one actor. Everything that reads or
 * changes {@link #held} goes through that actor's mailbox, so nothing here is {@code volatile},
 * {@code synchronized} or atomic — the same arrangement {@code JobQueue} uses in
 * quarkus-gpu-broker, and the one {@code AiWorkspaceActorSystem} states as this group's rule.</p>
 *
 * <p>The renewal is scheduled against {@code self} rather than run from whoever asks. A dashboard
 * that lists every running tool gives each one a second to answer; working an answer out takes a
 * call to a model and can take a minute. Before this class, the renewal was started by the request
 * that noticed the answer was stale, on a bare virtual thread, and the answer it produced was kept
 * in a {@code volatile} field — so nothing renewed while nobody was looking, and an instance that
 * resolved a model name early kept that name for as long as it ran.</p>
 *
 * <p>{@link #tick} stays on the mailbox and is cheap. The model call runs on the managed thread
 * pool and comes back through the mailbox as {@link #store}, so a call that takes a minute never
 * delays {@link #current}.</p>
 */
public class ActivityWatcher {

    private static final Logger LOG = Logger.getLogger(ActivityWatcher.class.getName());

    /**
     * How often the held answer is examined.
     *
     * <p>Shorter than {@link ActivityAnswer#RETRY_AGE}, so an answer that may stand only a minute
     * is renewed within about a minute of falling due. Examining costs nothing when the answer
     * still stands: {@link #tick} returns without asking anything.</p>
     */
    private static final long TICK_SECONDS = 30;

    private final ActivityWork work;

    private ActorRef<ActivityWatcher> self;
    private ExecutorService pool;
    private Scheduler scheduler;

    private ActivityAnswer held = ActivityAnswer.pending();

    /** Whether an answer is being worked out, so that ticks do not start a second one. */
    private boolean working;

    /**
     * @param work what reads the conversation and asks a model to name its subject
     */
    public ActivityWatcher(ActivityWork work) {
        this.work = work;
    }

    /**
     * Binds this actor's own reference and the pool the model call runs on.
     *
     * <p>Must run before {@link #startWatching}: the schedule is set against {@code self}, and the
     * model call is handed to {@code pool}.</p>
     *
     * @param self this actor's own reference
     * @param pool the actor system's managed thread pool
     */
    public void bind(ActorRef<ActivityWatcher> self, ExecutorService pool) {
        this.self = self;
        this.pool = pool;
    }

    /** Starts the periodic examination described in the class Javadoc. */
    public void startWatching() {
        scheduler = new Scheduler(1);
        scheduler.scheduleWithFixedDelay("activity", self, ActivityWatcher::tick,
                0, TICK_SECONDS, TimeUnit.SECONDS);
        LOG.info("ActivityWatcher started (every " + TICK_SECONDS + "s)");
    }

    /** Stops the schedule. Called when the actor system is torn down. */
    public void stopWatching() {
        if (scheduler != null) scheduler.close();
    }

    /**
     * On the mailbox, and cheap: renews the held answer only when it has stood longer than it may,
     * and hands the work that takes a model call to {@link #pool}.
     */
    void tick() {
        if (working || !held.isStale(Instant.now())) return;
        working = true;
        ActorRef<ActivityWatcher> me = self;
        me.tell(w -> {
            ActivityAnswer worked;
            try {
                worked = w.work.compute();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Could not work out what this instance is doing", e);
                worked = null;
            }
            ActivityAnswer result = worked;
            me.tell(x -> x.store(result));
        }, pool);
    }

    /**
     * On the mailbox: takes the worked-out answer, or releases the flag when there was none.
     *
     * @param answer what {@link ActivityWork#compute} produced, or {@code null} when it threw
     */
    void store(ActivityAnswer answer) {
        if (answer != null) held = answer;
        working = false;
    }

    /**
     * @return the answer as it stands; never null
     */
    public ActivityAnswer current() {
        return held;
    }
}
