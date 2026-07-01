package com.scivicslab.chatui.tmux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Small helper for running short-lived external commands ({@code mkfifo},
 * {@code tmux pipe-pane}, etc.) and collecting their result.
 *
 * <p>Reads both streams fully before waiting for exit; intended only for
 * commands whose output is small and bounded.
 */
final class ProcRunner {

    record Result(int exitCode, String stdout, String stderr) {
    }

    private ProcRunner() {
    }

    static Result run(List<String> argv, long timeoutMs) {
        try {
            Process p = new ProcessBuilder(argv).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new TmuxException("command timed out: " + String.join(" ", argv));
            }
            return new Result(p.exitValue(), out, err);
        } catch (IOException e) {
            throw new TmuxException("failed to run command: " + String.join(" ", argv), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmuxException("interrupted while running command: " + String.join(" ", argv), e);
        }
    }

    static Result runChecked(List<String> argv, long timeoutMs) {
        Result r = run(argv, timeoutMs);
        if (r.exitCode() != 0) {
            throw new TmuxException("command failed (exit " + r.exitCode() + "): "
                    + String.join(" ", argv) + System.lineSeparator() + r.stderr());
        }
        return r;
    }
}
