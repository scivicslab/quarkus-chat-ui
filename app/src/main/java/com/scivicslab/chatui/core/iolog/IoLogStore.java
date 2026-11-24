package com.scivicslab.chatui.core.iolog;

import com.scivicslab.pojoactor.core.ActorClosedException;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The I/O log of the instance, as seen by the rest of the application: the H2 store that persists the complete I/O
 * (the Sessions tab) plus the conversation, job and project session ids. Application-scoped so the store and the sessions
 * outlive any single turn.
 *
 * <p>The state (which session each conversation writes to, the opened store) belongs to one owner, an actor that runs
 * {@link IoLogSessions}; this class holds no lock and no state of its own. Every call is a message to that actor:</p>
 * <ul>
 *   <li>a write ({@link #record}) is {@code tell}'d, so the caller moves on, and the actor hands it on in the order it
 *       received it to the store's own actor ({@link ActorBackedLogStore});</li>
 *   <li>a question or an opening of a session is {@code ask}'d and returns after every write told before it;</li>
 *   <li>{@link #flush()} is an {@code ask} that returns when everything told before it is in the database, so a caller
 *       that needs "written" waits for exactly that;</li>
 *   <li>{@link #close()} is an {@code ask} behind the pending writes, and closes the store after them.</li>
 * </ul>
 *
 * <p>The DB path is a startup property ({@code chat-ui.iolog.db-path}, default {@code chat-ui-iolog}) with the instance's
 * HTTP port appended, so each instance opens its own file (e.g. {@code ./chat-ui-iolog-18090.mv.db}); the port suffix
 * keeps two instances from sharing one H2 store via {@code AUTO_SERVER}. Logging is best-effort: if the DB cannot be
 * opened, the rest of the app keeps working and the complete log is simply unavailable. A call after {@link #close()}
 * behaves as if logging were off.</p>
 */
@ApplicationScoped
public class IoLogStore {

    private static final Logger LOG = Logger.getLogger(IoLogStore.class.getName());

    @ConfigProperty(name = "chat-ui.iolog.db-path", defaultValue = "chat-ui-iolog")
    String dbPath;

    /**
     * Puts this store's database somewhere other than the configured path. Must be called before the first line is
     * written, which is when the file is opened.
     *
     * @param path the database path, without the {@code .mv.db} suffix
     */
    public void useDatabaseAt(String path) {
        this.dbPath = path;
    }

    // The instance's HTTP port, appended to the DB path so two instances on different ports never open
    // the same H2 file (H2 opens with AUTO_SERVER=TRUE; without this a second process would connect
    // into the first instance's database and the two would write to one shared store).
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    // Lossless MVStore compression: the logged content is repetitive, so compression shrinks the
    // .mv.db file with no loss. Applies to newly written data.
    @ConfigProperty(name = "chat-ui.iolog.compress", defaultValue = "true")
    boolean compress;

    // Recorded on every conversation session so that a database read outside this process can say
    // which build produced it.
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    /**
     * The owner of the session book. The settings are read by the owner when it first opens the database, after
     * injection and {@link #useDatabaseAt}; those writes reach it through the mailbox, which orders them before the
     * first message that needs them.
     */
    private final ActorRef<IoLogSessions> book = new ActorRef<>("io-log.sessions",
            new IoLogSessions(() -> new IoLogSessions.Settings(dbPath + "-" + httpPort, compress, appVersion)));

    /** A question for the owner; the answer comes after every write told before it. */
    private <R> R ask(Function<IoLogSessions, R> call, R whenClosed) {
        try {
            return book.ask(call).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ActorClosedException) return whenClosed;
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        } catch (ActorClosedException e) {
            return whenClosed;
        }
    }

    /**
     * The session this process has already opened for a conversation. Only looks: it never opens one, so a caller that
     * wants to read what was written cannot create an empty session by asking, and a write that did not happen shows as
     * {@code -1} instead of as an empty session ({@code LookupThatCreates_260914_oo01}). {@link #ensureSession} is the
     * one that opens.
     *
     * @return the session id, or -1 when this process has opened none for that conversation
     */
    public long sessionIdOf(String conversationSquadId) {
        return ask(b -> b.sessionIdOf(conversationSquadId), -1L);
    }

    /**
     * The session this process has already opened for a job; like {@link #sessionIdOf}, it only looks.
     * {@link #ensureJobSession} is the one that opens.
     *
     * @return the session id, or -1 when this process has opened none for that job
     */
    public long jobSessionIdOf(String jobActorName) {
        return ask(b -> b.jobSessionIdOf(jobActorName), -1L);
    }

    /**
     * Returns the given ConversationSquad's session id, opening a session on first use; -1 if unavailable. Each
     * ConversationSquad gets its own session id (ConversationSquads never share one), tagged via {@code workflowName} so
     * the Sessions view can filter by ConversationSquad. After a restart the ConversationSquad's session is still in the
     * DB and is continued, not replaced ({@code ConversationRestoreOnRestart_260904_oo01}).
     */
    public long ensureSession(String conversationSquadId) {
        return ask(b -> b.ensureSession(conversationSquadId), -1L);
    }

    /**
     * Returns the given job's session id, opening one on first use; -1 if unavailable. A job's lines are written to the
     * same database the conversations are in, which the log search reads ({@code JobRunsInTheLog_260915_oo01}). Named
     * apart from a conversation's session so that {@link #resumableConversationSquads()} does not build a
     * ConversationSquad out of a job at the next start-up.
     *
     * @param jobActorName the job's actor name, e.g. {@code project1/job-01}
     */
    public long ensureJobSession(String jobActorName) {
        return ask(b -> b.ensureJobSession(jobActorName), -1L);
    }

    /**
     * The session a project's own properties are written to, opening one on first use; -1 when logging is unavailable.
     * Unlike a job's session, this one is resumed rather than opened afresh ({@code ProjectProperty_260929_oo01}).
     *
     * @param projectId the project's id, e.g. {@code project1}
     */
    public long ensureProjectSession(String projectId) {
        return ask(b -> b.ensureProjectSession(projectId), -1L);
    }

    /**
     * The project's still-open session, or {@code -1} when the log holds none.
     *
     * @param projectId the project's id
     */
    public long findProjectSession(String projectId) {
        return ask(b -> b.findProjectSession(projectId), -1L);
    }

    /**
     * Ends the project's own session (called when the project is deleted); nothing is deleted from the log.
     *
     * @param projectId the project's id
     */
    public void endProjectSession(String projectId) {
        ask(b -> {
            b.endProjectSession(projectId);
            return null;
        }, null);
    }

    /**
     * The ids of the projects whose own session is still open, oldest first.
     *
     * @return the project ids, e.g. {@code "project3"}
     */
    public List<String> runningProjectIds() {
        return ask(IoLogSessions::runningProjectIds, List.of());
    }

    /**
     * Every ConversationSquad the log still holds an unfinished session for, most recent first. This is what the
     * ConversationSquad-to-actor structure is rebuilt from after a restart. Only {@code RUNNING} sessions count: a
     * conversation the user cleared has had its session ended, and must stay cleared.
     *
     * @return the ConversationSquad ids, e.g. {@code "project2/chat-01"}, without duplicates
     */
    public List<String> resumableConversationSquads() {
        return ask(IoLogSessions::resumableConversationSquads, List.of());
    }

    /**
     * Every ConversationSquad the log holds a session for, whether or not it was ended, most recent first. Ended ones are
     * not reopened, but their names stay on the sessions, so a new project must not be given one of them
     * ({@code ProjectDeletion_261011_oo01}).
     *
     * @return the ConversationSquad ids, e.g. {@code "project2/chat-01"}, without duplicates
     */
    public List<String> recordedConversationSquads() {
        return ask(IoLogSessions::recordedConversationSquads, List.of());
    }

    /**
     * The ConversationSquad's most recent session that was never ended, or {@code -1} when it has none. Only a
     * {@code RUNNING} session qualifies, so that "new conversation" is not undone by a restart.
     *
     * @param conversationSquadId the ConversationSquad's name
     * @return the session id to continue, or {@code -1} when none can be
     */
    public long findResumableSession(String conversationSquadId) {
        return ask(b -> b.findResumableSession(conversationSquadId), -1L);
    }

    /** The given ConversationSquad's current log session id, or -1 if none. */
    public long currentSessionId(String conversationSquadId) {
        return ask(b -> b.currentSessionId(conversationSquadId), -1L);
    }

    /**
     * Ends the given ConversationSquad's session (called on "new conversation" / clear): the one this process opened or,
     * when it opened none, the one the database still holds open for it. Nothing is deleted; the session is marked as
     * ended and every line it wrote stays where it is.
     *
     * @param conversationSquadId the ConversationSquad's name
     */
    public void resetSession(String conversationSquadId) {
        ask(b -> {
            b.resetSession(conversationSquadId);
            return null;
        }, null);
    }

    /**
     * The shared store (read side for the Sessions view). May be null if logging is disabled or this log is closed.
     * It is the store's own actor-backed handle: calls on it may come from any thread and are ordered behind the
     * writes told before.
     */
    public DistributedLogStore store() {
        return ask(IoLogSessions::store, null);
    }

    /**
     * Writes one entry to the given session. Any thread may call it: the write is told to the owner and handed on in
     * order. No-op if logging is off or the session id is invalid. A caller that needs the entry to be in the database
     * calls {@link #flush()} afterwards.
     */
    public void record(long sessionId, String node, String label, String content) {
        if (sessionId < 0) {
            return;
        }
        book.tell(b -> b.record(sessionId, node, label, content)).exceptionally(e -> {
            if (!(e instanceof ActorClosedException) && !(e.getCause() instanceof ActorClosedException)) {
                LOG.log(Level.WARNING, "I/O log record failed", e);
            }
            return null;
        });
    }

    /**
     * Returns once everything told to this log before the call is in the database, and so readable. A no-op when
     * logging is off or the log is closed.
     */
    public void flush() {
        ask(b -> {
            b.flush();
            return null;
        }, null);
    }

    /**
     * Deletes one session and all of its logs / node_results. Refuses to delete the active conversation session.
     * Returns the number of sessions deleted (1 or 0), or -1 on error.
     */
    public int deleteSession(long id) {
        return ask(b -> b.deleteSession(id), 0);
    }

    /**
     * Deletes every session started more than {@code days} days ago (and its logs / node_results), excluding the active
     * conversation session. Returns the number of sessions deleted, or -1 on error.
     */
    public int deleteSessionsOlderThan(int days) {
        return ask(b -> b.deleteSessionsOlderThan(days), 0);
    }

    /**
     * The same JDBC URL H2LogStore uses, for a short-lived connection of one's own. Used by {@link IoLogSearch}, which
     * reads across every session in the file rather than through the store's one-conversation-at-a-time view.
     */
    String jdbcUrl() {
        return IoLogSessions.jdbcUrl(new IoLogSessions.Settings(dbPath + "-" + httpPort, compress, appVersion));
    }

    /**
     * Closes the store after the writes told before this call (an {@code ask} behind them, so none is lost), then stops
     * the owner. Later calls behave as if logging were off: {@link #store()} answers {@code null}, a write is dropped
     * without a word, a lookup finds no session.
     */
    @PreDestroy
    void shutdown() {
        ask(b -> {
            b.close();
            return null;
        }, null);
        book.close();
    }

    /** The same as {@link #shutdown()}. */
    public void close() {
        shutdown();
    }
}
