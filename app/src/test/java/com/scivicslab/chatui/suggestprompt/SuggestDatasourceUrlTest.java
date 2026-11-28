package com.scivicslab.chatui.suggestprompt;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Where the Suggest prompt DB is, and how the datasource {@code suggest} gets that as its URL. No DB is opened. */
class SuggestDatasourceUrlTest {

    @Test
    void theFileIsBesideTheIoLogDbWithThePortInItsName() {
        Path file = SuggestDatasourceUrl.fileFor("/var/lib/chat-ui/chat-ui-iolog", 28030);
        System.out.println(file + "  " + SuggestDatasourceUrl.of("/var/lib/chat-ui/chat-ui-iolog", 28030));
        assertEquals(Path.of("/var/lib/chat-ui/suggest-prompt-28030"), file);
        assertEquals("jdbc:h2:file:/var/lib/chat-ui/suggest-prompt-28030",
                SuggestDatasourceUrl.of("/var/lib/chat-ui/chat-ui-iolog", 28030));
    }

    @Test
    void aRelativeIoLogPathIsTakenFromTheWorkingDirectoryLikeTheIoLogDbIs() {
        Path file = SuggestDatasourceUrl.fileFor("chat-ui-iolog", 18090);
        System.out.println(file);
        assertEquals(Path.of("").toAbsolutePath().resolve("suggest-prompt-18090"), file);
        assertEquals(Path.of("").toAbsolutePath().resolve("data/suggest-prompt-1"),
                SuggestDatasourceUrl.fileFor("data/iolog", 1));
    }

    @Test
    void twoPortsGiveTwoFilesAndTheFileIsNotTheIoLogOne() {
        assertNotEquals(SuggestDatasourceUrl.of("/d/x", 1), SuggestDatasourceUrl.of("/d/x", 2));
        assertNotEquals("/d/x-1", SuggestDatasourceUrl.fileFor("/d/x", 1).toString());
    }

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder().withInterceptors(new SuggestDatasourceConfig())
                .withSources(new PropertiesConfigSource(properties, "test", 100)).build();
    }

    @Test
    void theDatasourceUrlIsDerivedFromTheIoLogPathAndThePortUnderBothSpellingsOfTheKey() {
        SmallRyeConfig c = config(Map.of("chat-ui.iolog.db-path", "/data/chat-ui-iolog", "quarkus.http.port", "28030"));
        String quoted = c.getValue("quarkus.datasource.\"suggest\".jdbc.url", String.class);
        String plain = c.getValue("quarkus.datasource.suggest.jdbc.url", String.class);
        System.out.println(quoted + "  " + plain);
        assertEquals("jdbc:h2:file:/data/suggest-prompt-28030", quoted);
        assertEquals(quoted, plain);
    }

    @Test
    void withNoSettingsItUsesTheDefaultsOfTheIoLogStore() {
        String url = config(Map.of()).getValue("quarkus.datasource.\"suggest\".jdbc.url", String.class);
        System.out.println(url);
        assertEquals("jdbc:h2:file:" + Path.of("").toAbsolutePath().resolve("suggest-prompt-8080"), url);
    }

    @Test
    void aUrlSetByHandWinsAndOtherKeysAreLeftAlone() {
        SmallRyeConfig c = config(Map.of("quarkus.datasource.\"suggest\".jdbc.url", "jdbc:h2:mem:x",
                "chat-ui.iolog.db-path", "/data/chat-ui-iolog"));
        assertEquals("jdbc:h2:mem:x", c.getValue("quarkus.datasource.\"suggest\".jdbc.url", String.class));
        assertEquals(java.util.Optional.empty(), c.getOptionalValue("quarkus.datasource.\"other\".jdbc.url", String.class));
    }
}
