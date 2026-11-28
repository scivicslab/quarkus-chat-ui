package com.scivicslab.chatui.suggestprompt;

import java.nio.file.Path;

/**
 * Where the Suggest prompt DB file is: in the directory of the I/O log DB, in a file of its own named
 * {@code suggest-prompt-<port>}. The port keeps two instances on one machine from opening the same file, as it does for
 * the I/O log DB ({@code chat-ui.iolog.db-path} with the port appended).
 */
public final class SuggestDatasourceUrl {

    /** The file name before the port. */
    public static final String FILE_PREFIX = "suggest-prompt-";

    private SuggestDatasourceUrl() {
    }

    /**
     * @param ioLogDbPath the value of {@code chat-ui.iolog.db-path}, without the port or the {@code .mv.db} suffix; its
     *                    directory is the one the Suggest prompt DB goes to
     * @param port        the instance's HTTP port
     * @return the absolute path of the DB file, without the {@code .mv.db} suffix
     */
    public static Path fileFor(String ioLogDbPath, int port) {
        Path dir = Path.of(ioLogDbPath).toAbsolutePath().getParent();
        return dir.resolve(FILE_PREFIX + port);
    }

    /** @return the JDBC URL of that file */
    public static String of(String ioLogDbPath, int port) {
        return "jdbc:h2:file:" + fileFor(ioLogDbPath, port);
    }
}
