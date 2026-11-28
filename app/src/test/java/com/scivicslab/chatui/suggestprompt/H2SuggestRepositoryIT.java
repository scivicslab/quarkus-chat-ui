package com.scivicslab.chatui.suggestprompt;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link H2SuggestRepository} against a real H2 file. Integration test: it opens a database. A "restart" here closes the
 * connection pool, which closes the H2 database, and opens a new pool on the same file.
 */
class H2SuggestRepositoryIT {

    private static final String SQUAD = "project1/chat-01";

    @TempDir
    Path dir;

    private AgroalDataSource pool;
    private H2SuggestRepository repository;

    private AgroalDataSource open() throws Exception {
        return AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier().connectionPoolConfiguration(
                p -> p.maxSize(3).connectionFactoryConfiguration(
                        f -> f.jdbcUrl(SuggestDatasourceUrl.of(dir.resolve("chat-ui-iolog").toString(), 28030))
                                .connectionProviderClass(org.h2.Driver.class))));
    }

    @BeforeEach
    void start() throws Exception {
        pool = open();
        repository = new H2SuggestRepository(pool);
    }

    @AfterEach
    void stop() {
        pool.close();
    }

    private void restart() throws Exception {
        pool.close();
        pool = open();
        repository = new H2SuggestRepository(pool);
    }

    private static SuggestFrame frame(int epoch, int revision) {
        return new SuggestFrame(SQUAD, epoch, "superior", "Rebuilding the connector, 日本語も。", 7, revision, 1234L, List.of(
                new SuggestFrame.Topic(1, "Rename", "rename step", "implement", "project1/chat-33", "open", 6,
                        List.of(new SuggestFrame.Step(1, "Move classes", "done", "n1"),
                                new SuggestFrame.Step(2, "Fix tests", "not-started", "")),
                        List.of(new SuggestFrame.Term("epoch", 3), new SuggestFrame.Term("zeta", 2),
                                new SuggestFrame.Term("alpha", 5))),
                new SuggestFrame.Topic(2, "Design", "", "design", null, "dropped", 4, List.of(), List.of())));
    }

    private int count(String table) throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getInt(1);
        }
    }

    @Test
    void aSavedFrameIsLoadedBackAsItWasIncludingTheOrderOfTheTerms() {
        repository.save(frame(1, 3));
        SuggestFrame loaded = repository.loadCurrent(SQUAD).orElseThrow();
        System.out.println(SuggestDigest.of(loaded));
        assertEquals(frame(1, 3), loaded);
        assertEquals(List.of("epoch", "zeta", "alpha"),
                loaded.topics().get(0).terms().stream().map(SuggestFrame.Term::term).toList());
    }

    @Test
    void aSquadWithNoRowGivesEmptyAndEpochZero() {
        assertEquals(Optional.empty(), repository.loadCurrent("nobody/none"));
        assertEquals(0, repository.maxEpoch("nobody/none"));
    }

    @Test
    void saveReplacesTheRowsOfThatGenerationOnlyAndTheOlderOnesStay() throws Exception {
        repository.save(frame(1, 3));
        repository.save(SuggestFrame.empty(SQUAD, 2, "subordinate"));
        assertEquals(2, repository.maxEpoch(SQUAD));
        SuggestFrame current = repository.loadCurrent(SQUAD).orElseThrow();
        assertEquals(2, current.epoch());
        assertTrue(current.topics().isEmpty());
        assertEquals(2, count("suggest_topic"), "the topics of generation 1 are still there");
        assertEquals(2, count("suggest_step"));
        assertEquals(3, count("suggest_term"));

        SuggestFrame smaller = new SuggestFrame(SQUAD, 1, "superior", "", 8, 4, 2L, List.of(
                new SuggestFrame.Topic(1, "Only", "", "write", null, "open", 8, List.of(), List.of())));
        repository.save(smaller);
        assertEquals(1, count("suggest_topic"), "generation 1 now has 1 topic; generation 2 has none");
        assertEquals(0, count("suggest_step"));
        assertEquals(0, count("suggest_term"));
    }

    @Test
    void otherSquadsAreNotTouched() {
        repository.save(frame(1, 3));
        repository.save(new SuggestFrame("project1/chat-02", 1, "standalone", "other", 1, 1, 1L, List.of()));
        repository.save(SuggestFrame.empty(SQUAD, 1, "standalone"));
        assertEquals("other", repository.loadCurrent("project1/chat-02").orElseThrow().overview());
        assertTrue(repository.loadCurrent(SQUAD).orElseThrow().topics().isEmpty());
    }

    @Test
    void aFailedSaveLeavesTheOldRowsInPlace() {
        repository.save(frame(1, 3));
        SuggestFrame bad = new SuggestFrame(SQUAD, 1, "superior", "x".repeat(5000), 9, 9, 9L, List.of());
        try {
            repository.save(bad);
        } catch (IllegalStateException expected) {
            System.out.println("refused: " + expected.getCause().getMessage().lines().findFirst().orElse(""));
        }
        assertEquals(frame(1, 3), repository.loadCurrent(SQUAD).orElseThrow(), "rolled back, not half replaced");
    }

    @Test
    void theRowsAreBackAfterARestartOfTheDatabase() throws Exception {
        repository.save(frame(1, 3));
        repository.save(frame(2, 1));
        repository.replaceQuestions(SuggestFixtures.questions());
        restart();
        assertEquals(frame(2, 1), repository.loadCurrent(SQUAD).orElseThrow());
        assertEquals(2, repository.maxEpoch(SQUAD));
        assertEquals(SuggestFixtures.questions(), repository.questions());
        assertTrue(dir.resolve("suggest-prompt-28030.mv.db").toFile().isFile(), "the file named by the rule");
        assertFalse(dir.resolve("chat-ui-iolog-28030.mv.db").toFile().exists(), "the I/O log's file is not this one");
    }

    @Test
    void theQuestionsKeepTheirOrderAndAreReplacedWhole() throws Exception {
        assertEquals(List.of(), repository.questions());
        repository.replaceQuestions(SuggestFixtures.questions());
        assertEquals(16, repository.questions().size());
        assertEquals("common-1", repository.questions().get(0).questionId());
        assertEquals("write-2", repository.questions().get(15).questionId());
        repository.replaceQuestions(List.of(new SuggestQuestion("write", "write-1", "Only {topic}")));
        assertEquals(List.of(new SuggestQuestion("write", "write-1", "Only {topic}")), repository.questions());
    }

    @Test
    void creatingTheRepositoryAgainKeepsWhatIsThere() throws Exception {
        repository.save(frame(1, 3));
        new H2SuggestRepository(pool).ensureSchema();
        assertEquals(frame(1, 3), repository.loadCurrent(SQUAD).orElseThrow());
    }
}
