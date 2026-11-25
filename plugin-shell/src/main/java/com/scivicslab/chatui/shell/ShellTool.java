package com.scivicslab.chatui.shell;

import com.scivicslab.chatui.kernel.BlockingJob;
import com.scivicslab.chatui.kernel.StateOwner;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one shell command and returns what it printed.
 *
 * <p>Started through {@code /bin/sh -c}, so the command is written the way it would be typed, with
 * pipes and redirection. The working directory is the conversation's own, and the whole output —
 * standard output and standard error together, in the order the process wrote them — goes into the
 * I/O log with the exit status, which is the point of running it here rather than in a terminal.</p>
 *
 * <p>Two bounds, and no more. A command that has not finished within {@link #TIMEOUT_SECONDS} is
 * killed, and output past {@link #MAX_OUTPUT} characters is cut with a line saying how much there
 * was; both are about a conversation that would otherwise sit waiting or be handed more text than
 * it can read. Nothing here restricts what the command may do: a shell cannot be made to do less
 * than a shell, and the instance that loads this plugin is the one that already reaches the web.</p>
 */
public final class ShellTool {

    /** How long a command may run before it is killed. */
    public static final int TIMEOUT_SECONDS = 120;

    /** How much of the output the conversation is given. */
    public static final int MAX_OUTPUT = 30_000;

    private ShellTool() {
    }

    /**
     * Runs {@code command} through {@code /bin/sh -c} in {@code workingDirectory}.
     *
     * @param workingDirectory where the command starts; the process's own directory when null
     * @param command          the command line, as it would be typed
     * @return the exit status and the output, or an {@code error: ...} the conversation feeds back
     */
    public static String run(Path workingDirectory, String command) {
        return run(workingDirectory, command, TIMEOUT_SECONDS);
    }

    /** As {@link #run(Path, String)} with the limit given, so a test need not wait out the real one. */
    static String run(Path workingDirectory, String command, int timeoutSeconds) {
        if (command == null || command.isBlank()) return "error: command required";
        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/sh", "-c", command));
        pb.redirectErrorStream(true);   // one stream, in the order it was written
        if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
            pb.directory(workingDirectory.toFile());
        }
        Process process = null;
        try {
            process = pb.start();
            process.getOutputStream().close();   // nothing to type at it; a prompt would hang
            return waitForIt(process, timeoutSeconds);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        } catch (InterruptedException e) {
            if (process != null) killWithItsChildren(process);
            Thread.currentThread().interrupt();
            return "error: interrupted while waiting for the command";
        }
    }

    /**
     * Waits for the command with a deadline that holds whether or not it prints. The output is read by a job of its
     * own, which hands each piece to the actor that owns what has been read; the deadline is the wait for that job. Past
     * it the command and what it started are killed, and what was printed so far comes back with the notice. (The
     * output was once read to its end first and only then waited for, so a command that printed nothing and ran long
     * was never ended at the limit.)
     */
    private static String waitForIt(Process process, int timeoutSeconds) throws InterruptedException {
        try (StateOwner<Output> read = new StateOwner<>("shell-output", new Output())) {
            BlockingJob<Void> reader = BlockingJob.start("shell-output-reader", () -> {
                try (Reader in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] chunk = new char[8192];
                    for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                        String piece = new String(chunk, 0, n);
                        read.tell(o -> o.add(piece));
                    }
                }
                return null;
            }, null);
            boolean ended;
            try {
                reader.result().get(timeoutSeconds, TimeUnit.SECONDS);
                ended = true;
            } catch (TimeoutException e) {
                ended = false;
            } catch (ExecutionException e) {
                return "error: " + e.getCause().getMessage();
            }
            if (ended) {
                // The output has ended; the command is ending too.
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    killWithItsChildren(process);
                    return "error: killed after " + timeoutSeconds + " seconds\n" + read.ask(Output::text);
                }
                return "exit " + process.exitValue() + "\n" + read.ask(Output::text);
            }
            killWithItsChildren(process);
            // The pipe closes when everything that held it is gone; what is not read by then is left.
            try {
                reader.result().get(2, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                reader.cancel();
            }
            return "error: killed after " + timeoutSeconds + " seconds\n" + read.ask(Output::text);
        }
    }

    /** Ends the command and everything it started: a shell's child holds the output pipe open after the shell is gone. */
    private static void killWithItsChildren(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** What a command has printed, kept by one actor: the opening, and how much there was in all. */
    static final class Output {
        private final StringBuilder opening = new StringBuilder();
        private long total;

        void add(String piece) {
            total += piece.length();
            int room = MAX_OUTPUT - opening.length();
            if (room > 0) opening.append(piece, 0, Math.min(room, piece.length()));
        }

        String text() {
            if (total <= MAX_OUTPUT) return opening.toString();
            return opening + "\n[truncated " + total + " chars total]";
        }
    }
}
