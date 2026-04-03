package com.scivicslab.coderagent.cli.process;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Manages a CLI LLM subprocess (claude, codex, etc.) using stream-json I/O.
 *
 * <p>The binary name and API key environment variable are supplied at construction
 * time, making this class usable by both Claude and Codex providers without
 * duplication.</p>
 *
 * <p>With {@code --input-format stream-json}, the process stays alive across turns.
 * {@link #sendPrompt} writes to stdin and reads stdout until a result event arrives.</p>
 */
public class CliProcess {

    private static final Logger logger = Logger.getLogger(CliProcess.class.getName());

    private static final Pattern ANSI_PATTERN = Pattern.compile(
        "\\x1b\\[[0-9;?]*[a-zA-Z]|\\x1b\\][^\u0007]*\u0007|\\x1b[^\\[\\]].?"
    );

    private final String binary;
    private final String apiKeyEnvVar;
    private final StreamEventParser parser = new StreamEventParser();

    private CliConfig config;
    private String lastSessionId;
    private Process currentProcess;
    private OutputStream stdinStream;
    private BufferedReader stdoutReader;
    private volatile String apiKey;

    public CliProcess(String binary, String apiKeyEnvVar, CliConfig config) {
        this.binary = binary;
        this.apiKeyEnvVar = apiKeyEnvVar;
        this.config = config;
    }

    public CliConfig getConfig() { return config; }
    public void setConfig(CliConfig config) { this.config = config; }
    public void setApiKey(String key) { this.apiKey = key; }
    public String getLastSessionId() { return lastSessionId; }
    public boolean isAlive() { return currentProcess != null && currentProcess.isAlive(); }

    private void startProcess() throws IOException {
        List<String> cmd = buildCommand();
        logger.fine(() -> "Starting CLI: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("CLAUDECODE");
        pb.environment().remove("CLAUDE_CODE_ENTRYPOINT");
        if (apiKey != null && !apiKey.isBlank()) {
            pb.environment().put(apiKeyEnvVar, apiKey);
        }
        if (config.workingDir() != null) {
            pb.directory(new File(config.workingDir()));
        }
        pb.redirectErrorStream(false);
        currentProcess = pb.start();

        stdinStream = currentProcess.getOutputStream();
        stdoutReader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));

        Process proc = currentProcess;
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String msg = line;
                    logger.fine(() -> "stderr: " + msg);
                }
            } catch (IOException ignored) {}
        });
    }

    /**
     * Sends a prompt and streams response events to the callback.
     * The process stays alive after a turn completes.
     */
    public int sendPrompt(String prompt, StreamCallback callback) throws IOException {
        if (currentProcess == null || !currentProcess.isAlive()) startProcess();

        writeUserMessage(prompt);

        String line;
        while ((line = stdoutReader.readLine()) != null) {
            line = stripAnsi(line).trim();
            if (line.isEmpty() || !line.startsWith("{")) continue;

            String logLine = line;
            logger.info(() -> "stdout: " + logLine);

            StreamEvent event = parser.parse(line);
            if (event == null) continue;

            if ("result".equals(event.type()) && event.sessionId() != null) {
                lastSessionId = event.sessionId();
            }
            if (callback != null) callback.onEvent(event);
            if ("result".equals(event.type())) return 0;
        }

        int exitCode = 0;
        Process proc = currentProcess;
        if (proc != null) {
            try { exitCode = proc.waitFor(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (callback != null) callback.onComplete(exitCode);

        currentProcess = null;
        stdinStream = null;
        stdoutReader = null;
        return exitCode;
    }

    public void cancel() {
        Process p = currentProcess;
        currentProcess = null;
        stdinStream = null;
        stdoutReader = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            logger.info(binary + " CLI process cancelled");
        }
    }

    public void writeUserMessage(String text) throws IOException {
        if (stdinStream == null) throw new IOException("No active process stdin");
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
            + escapeJsonString(text) + "}}\n";
        stdinStream.write(json.getBytes(StandardCharsets.UTF_8));
        stdinStream.flush();
    }

    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        if (config.model() != null) { cmd.add("--model"); cmd.add(config.model()); }
        if (config.systemPrompt() != null) { cmd.add("--system-prompt"); cmd.add(config.systemPrompt()); }
        if (config.maxTurns() > 0) { cmd.add("--max-turns"); cmd.add(String.valueOf(config.maxTurns())); }
        if (config.sessionId() != null) { cmd.add("--resume"); cmd.add(config.sessionId()); }
        if (config.continueSession()) cmd.add("-c");
        if (config.allowedTools() != null) {
            for (String tool : config.allowedTools()) { cmd.add("--allowedTools"); cmd.add(tool); }
        }
        return cmd;
    }

    static String stripAnsi(String s) {
        return ANSI_PATTERN.matcher(s).replaceAll("");
    }

    static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    public interface StreamCallback {
        void onEvent(StreamEvent event);
        default void onComplete(int exitCode) {}
    }
}
