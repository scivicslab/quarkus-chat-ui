package com.scivicslab.chatui.tmux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Case-B output monitor: tees a tmux pane's raw byte stream into a named pipe
 * (fifo) and blocking-reads it on a virtual thread.
 *
 * <p>Every time bytes arrive from the pane, {@link #onActivity} is invoked. This
 * gives the {@code OutputWatcher} a faithful "the program is still writing"
 * signal: the thinking spinner redraws produce bytes, so the stream only goes
 * silent when Claude truly stops. The raw bytes themselves are not parsed here
 * (the settled screen is read separately via {@code capture-pane}); an optional
 * {@code onBytes} consumer is provided for tests and diagnostics.
 *
 * <p>Blocking the read on a virtual thread is the intended POJO-actor idiom: no
 * polling timer, no thread pool — just a thread parked in {@code read()} until
 * bytes appear.
 */
public final class PipePaneReader implements AutoCloseable {

    private static final long CMD_TIMEOUT_MS = 5_000;

    private final String sessionId;
    private final Runnable onActivity;
    private final Consumer<byte[]> onBytes; // nullable

    private Path fifo;
    private Thread readerThread;
    private volatile boolean running;

    /**
     * @param sessionId  the tmux session whose pane output is monitored
     * @param onActivity invoked (on the reader thread) whenever pane bytes arrive
     */
    public PipePaneReader(String sessionId, Runnable onActivity) {
        this(sessionId, onActivity, null);
    }

    /**
     * @param sessionId  the tmux session whose pane output is monitored
     * @param onActivity invoked (on the reader thread) whenever pane bytes arrive
     * @param onBytes    optional consumer of the raw bytes read (may be {@code null})
     */
    public PipePaneReader(String sessionId, Runnable onActivity, Consumer<byte[]> onBytes) {
        this.sessionId = sessionId;
        this.onActivity = onActivity;
        this.onBytes = onBytes;
    }

    /**
     * Creates the fifo, starts the blocking reader thread, and attaches
     * {@code tmux pipe-pane} so pane output flows into the fifo.
     *
     * @throws IOException if the fifo cannot be created
     */
    public void start() throws IOException {
        Path dir = Files.createTempDirectory("chatui-pipe-" + sessionId + "-");
        fifo = dir.resolve("pane.fifo");
        ProcRunner.runChecked(List.of("mkfifo", fifo.toString()), CMD_TIMEOUT_MS);

        running = true;
        readerThread = Thread.ofVirtual()
                .name("pipe-pane-reader-" + sessionId)
                .start(this::readLoop);

        // Attach the pipe after the reader thread exists so the fifo write end
        // (cat) and read end rendezvous instead of either blocking forever.
        ProcRunner.runChecked(TmuxCommands.pipePane(sessionId, "cat >> " + fifo), CMD_TIMEOUT_MS);
    }

    private void readLoop() {
        // Opening the fifo for read blocks until the writer (cat) opens it.
        try (InputStream in = Files.newInputStream(fifo)) {
            byte[] buf = new byte[8192];
            int n;
            while (running && (n = in.read(buf)) != -1) {
                if (n > 0) {
                    onActivity.run();
                    if (onBytes != null) {
                        onBytes.accept(Arrays.copyOf(buf, n));
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                throw new TmuxException("pipe-pane read failed for session " + sessionId, e);
            }
            // otherwise close() is in progress; ignore.
        }
    }

    /**
     * Detaches the pipe, stops the reader, and removes the fifo. Safe to call
     * more than once.
     */
    @Override
    public void close() {
        running = false;
        try {
            ProcRunner.run(TmuxCommands.pipePaneOff(sessionId), CMD_TIMEOUT_MS);
        } catch (RuntimeException ignore) {
            // session may already be gone
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (fifo != null) {
            try {
                Files.deleteIfExists(fifo);
                Files.deleteIfExists(fifo.getParent());
            } catch (IOException ignore) {
                // best effort
            }
        }
    }
}
