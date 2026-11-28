package com.scivicslab.chatui.suggestprompt;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The datasource {@code suggest} as the application configures it: the named datasource exists, its URL is made from
 * {@code chat-ui.iolog.db-path} and the HTTP port, and the repository bean works on it. {@code @QuarkusTest}, so this is
 * an integration test that runs with the production configuration (only the DB directory is a temporary one).
 */
@QuarkusTest
@TestProfile(SuggestDatasourceIT.Profile.class)
class SuggestDatasourceIT {

    static final Path DIR = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("suggest-ds-it").toAbsolutePath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("chat-ui.iolog.db-path", DIR.resolve("chat-ui-iolog").toString());
        }
    }

    @Inject
    @DataSource("suggest")
    AgroalDataSource suggest;

    @Inject
    SuggestRepository repository;

    @Test
    void theNamedDatasourceOpensTheFileBesideTheIoLogDbWithThePortInItsName() throws Exception {
        String url = suggest.getConfiguration().connectionPoolConfiguration().connectionFactoryConfiguration().jdbcUrl();
        System.out.println(url);
        assertTrue(url.startsWith("jdbc:h2:file:" + DIR + "/suggest-prompt-"), url);
        try (Connection c = suggest.getConnection()) {
            assertTrue(c.getMetaData().getURL().contains("suggest-prompt-"));
        }
    }

    @Test
    void theRepositoryBeanSavesAndLoadsOnIt() {
        SuggestFrame frame = new SuggestFrame("project1/it", 1, "standalone", "o", 1, 1, 1L, List.of());
        repository.save(frame);
        assertEquals(frame, repository.loadCurrent("project1/it").orElseThrow());
        assertEquals(1, repository.maxEpoch("project1/it"));
    }
}
