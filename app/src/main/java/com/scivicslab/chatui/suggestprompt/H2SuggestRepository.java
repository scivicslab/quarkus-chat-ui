package com.scivicslab.chatui.suggestprompt;

import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link SuggestRepository} on H2, through the Quarkus datasource {@code suggest} (Agroal). The work frame tables are
 * keyed by {@code squad} and {@code epoch}; a generation's rows are replaced in one transaction, and nothing deletes the
 * rows of another generation or of a conversation that is gone.
 */
@ApplicationScoped
public class H2SuggestRepository implements SuggestRepository {

    private final javax.sql.DataSource dataSource;

    @Inject
    public H2SuggestRepository(@DataSource("suggest") javax.sql.DataSource dataSource) {
        this.dataSource = dataSource;
        ensureSchema();
    }

    /** Creates the tables that are not there. Safe to call again. */
    final void ensureSchema() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS suggest_frame (squad VARCHAR(255) NOT NULL, epoch INT NOT NULL, "
                    + "role VARCHAR(16) NOT NULL, overview VARCHAR(2000) NOT NULL, digested_turn INT NOT NULL, "
                    + "revision INT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (squad, epoch))");
            s.execute("CREATE TABLE IF NOT EXISTS suggest_topic (squad VARCHAR(255) NOT NULL, epoch INT NOT NULL, "
                    + "topic_id INT NOT NULL, title VARCHAR(1000) NOT NULL, summary VARCHAR(2000) NOT NULL, "
                    + "work_type VARCHAR(16) NOT NULL, delegate_squad VARCHAR(255), status VARCHAR(16) NOT NULL, "
                    + "last_turn INT NOT NULL, PRIMARY KEY (squad, epoch, topic_id))");
            s.execute("CREATE TABLE IF NOT EXISTS suggest_step (squad VARCHAR(255) NOT NULL, epoch INT NOT NULL, "
                    + "topic_id INT NOT NULL, seq INT NOT NULL, title VARCHAR(1000) NOT NULL, "
                    + "progress VARCHAR(16) NOT NULL, note VARCHAR(2000) NOT NULL, "
                    + "PRIMARY KEY (squad, epoch, topic_id, seq))");
            // position keeps the order the terms came in, which the work frame's JSON shows.
            s.execute("CREATE TABLE IF NOT EXISTS suggest_term (squad VARCHAR(255) NOT NULL, epoch INT NOT NULL, "
                    + "topic_id INT NOT NULL, term VARCHAR(500) NOT NULL, turn INT NOT NULL, position INT NOT NULL, "
                    + "PRIMARY KEY (squad, epoch, topic_id, term))");
            // position keeps the table order of the questions (common-1 ... write-2).
            s.execute("CREATE TABLE IF NOT EXISTS suggest_question (work_type VARCHAR(16) NOT NULL, "
                    + "question_id VARCHAR(64) NOT NULL PRIMARY KEY, template VARCHAR(2000) NOT NULL, "
                    + "position INT NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("The Suggest prompt tables could not be created", e);
        }
    }

    @Override
    public int maxEpoch(String squad) {
        try (Connection c = dataSource.getConnection()) {
            return maxEpoch(c, squad);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int maxEpoch(Connection c, String squad) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT MAX(epoch) FROM suggest_frame WHERE squad = ?")) {
            p.setString(1, squad);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getInt(1) : 0; // MAX over no row is NULL, which getInt reads as 0
            }
        }
    }

    @Override
    public Optional<SuggestFrame> loadCurrent(String squad) {
        try (Connection c = dataSource.getConnection()) {
            int epoch = maxEpoch(c, squad);
            if (epoch == 0) return Optional.empty();
            String role;
            String overview;
            int digested;
            int revision;
            long updatedAt;
            try (PreparedStatement p = c.prepareStatement("SELECT role, overview, digested_turn, revision, updated_at "
                    + "FROM suggest_frame WHERE squad = ? AND epoch = ?")) {
                p.setString(1, squad);
                p.setInt(2, epoch);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    role = r.getString(1);
                    overview = r.getString(2);
                    digested = r.getInt(3);
                    revision = r.getInt(4);
                    updatedAt = r.getLong(5);
                }
            }
            List<SuggestFrame.Topic> topics = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT topic_id, title, summary, work_type, delegate_squad, "
                    + "status, last_turn FROM suggest_topic WHERE squad = ? AND epoch = ? ORDER BY topic_id")) {
                p.setString(1, squad);
                p.setInt(2, epoch);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        int id = r.getInt(1);
                        topics.add(new SuggestFrame.Topic(id, r.getString(2), r.getString(3), r.getString(4),
                                r.getString(5), r.getString(6), r.getInt(7), steps(c, squad, epoch, id),
                                terms(c, squad, epoch, id)));
                    }
                }
            }
            return Optional.of(new SuggestFrame(squad, epoch, role, overview, digested, revision, updatedAt, topics));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<SuggestFrame.Step> steps(Connection c, String squad, int epoch, int topicId) throws SQLException {
        List<SuggestFrame.Step> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT seq, title, progress, note FROM suggest_step "
                + "WHERE squad = ? AND epoch = ? AND topic_id = ? ORDER BY seq")) {
            p.setString(1, squad);
            p.setInt(2, epoch);
            p.setInt(3, topicId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new SuggestFrame.Step(r.getInt(1), r.getString(2), r.getString(3), r.getString(4)));
            }
        }
        return out;
    }

    private static List<SuggestFrame.Term> terms(Connection c, String squad, int epoch, int topicId) throws SQLException {
        List<SuggestFrame.Term> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT term, turn FROM suggest_term "
                + "WHERE squad = ? AND epoch = ? AND topic_id = ? ORDER BY position")) {
            p.setString(1, squad);
            p.setInt(2, epoch);
            p.setInt(3, topicId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new SuggestFrame.Term(r.getString(1), r.getInt(2)));
            }
        }
        return out;
    }

    @Override
    public void save(SuggestFrame f) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (String table : new String[]{"suggest_term", "suggest_step", "suggest_topic", "suggest_frame"}) {
                    try (PreparedStatement p = c.prepareStatement("DELETE FROM " + table + " WHERE squad = ? AND epoch = ?")) {
                        p.setString(1, f.squad());
                        p.setInt(2, f.epoch());
                        p.executeUpdate();
                    }
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO suggest_frame (squad, epoch, role, overview, "
                        + "digested_turn, revision, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    p.setString(1, f.squad());
                    p.setInt(2, f.epoch());
                    p.setString(3, f.role());
                    p.setString(4, f.overview());
                    p.setInt(5, f.digestedTurn());
                    p.setInt(6, f.revision());
                    p.setLong(7, f.updatedAt());
                    p.executeUpdate();
                }
                for (SuggestFrame.Topic t : f.topics()) {
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO suggest_topic (squad, epoch, topic_id, "
                            + "title, summary, work_type, delegate_squad, status, last_turn) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        p.setString(1, f.squad());
                        p.setInt(2, f.epoch());
                        p.setInt(3, t.topicId());
                        p.setString(4, t.title());
                        p.setString(5, t.summary());
                        p.setString(6, t.workType());
                        p.setString(7, t.delegateSquad());
                        p.setString(8, t.status());
                        p.setInt(9, t.lastTurn());
                        p.executeUpdate();
                    }
                    for (SuggestFrame.Step s : t.steps()) {
                        try (PreparedStatement p = c.prepareStatement("INSERT INTO suggest_step (squad, epoch, topic_id, "
                                + "seq, title, progress, note) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                            p.setString(1, f.squad());
                            p.setInt(2, f.epoch());
                            p.setInt(3, t.topicId());
                            p.setInt(4, s.seq());
                            p.setString(5, s.title());
                            p.setString(6, s.progress());
                            p.setString(7, s.note());
                            p.executeUpdate();
                        }
                    }
                    int position = 0;
                    for (SuggestFrame.Term term : t.terms()) {
                        try (PreparedStatement p = c.prepareStatement("INSERT INTO suggest_term (squad, epoch, topic_id, "
                                + "term, turn, position) VALUES (?, ?, ?, ?, ?, ?)")) {
                            p.setString(1, f.squad());
                            p.setInt(2, f.epoch());
                            p.setInt(3, t.topicId());
                            p.setString(4, term.term());
                            p.setInt(5, term.turn());
                            p.setInt(6, position++);
                            p.executeUpdate();
                        }
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<SuggestQuestion> questions() {
        List<SuggestQuestion> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT work_type, question_id, template FROM suggest_question ORDER BY position")) {
            while (r.next()) out.add(new SuggestQuestion(r.getString(1), r.getString(2), r.getString(3)));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @Override
    public void replaceQuestions(List<SuggestQuestion> questions) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM suggest_question");
                }
                int position = 0;
                for (SuggestQuestion q : questions) {
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO suggest_question (work_type, question_id, "
                            + "template, position) VALUES (?, ?, ?, ?)")) {
                        p.setString(1, q.workType());
                        p.setString(2, q.questionId());
                        p.setString(3, q.template());
                        p.setInt(4, position++);
                        p.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
