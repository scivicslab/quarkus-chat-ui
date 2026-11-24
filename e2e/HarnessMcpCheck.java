import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The E2E check of tasks/todo.md, "Shrinking the composed prompt", with a real harness CLI: a claude or codex
 * conversation calls the conversation's own tools over chat-ui's MCP server, and each call is a step of the
 * conversation's agent loop.
 *
 * <ol>
 * <li>Turn 1 calls {@code set_chat_name}: the name is set, the call is in the I/O log once, the turn answers, and the
 * turn's first request to the CLI is the question alone, with no tool text before it.</li>
 * <li>Turn 2 calls {@code ask_person}: the turn ends with the question, ended "asked".</li>
 * <li>Turn 3 is answered: the CLI's session survived the interrupted turn.</li>
 * </ol>
 *
 * <p>Usage: {@code java e2e/HarnessMcpCheck.java <port> <claude|codex> <chatId> [model]} against a running instance
 * started with the harness plugin. It uses the real CLI and its account.</p>
 */
public class HarnessMcpCheck {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).build();
    static int failures = 0;

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String url, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failures++;
    }

    /** Sends one prompt and waits until the conversation is idle again. */
    static void turn(String chat, String text) throws Exception {
        post(chat + "/chat", "{\"text\":" + quote(text) + "}");
        String status = "";
        for (int s = 0; s < 600; s++) {
            Thread.sleep(1000);
            status = get(chat + "/status");
            if (status.contains("\"busy\":false") && s > 2) return;
        }
        check(false, "the turn ended within 10 minutes", status);
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public static void main(String[] args) throws Exception {
        String base = "http://127.0.0.1:" + args[0];
        String provider = args[1];
        String id = args[2];
        String chat = base + "/api/projects/project1/chats/" + id;
        String conversation = "project1/chat-" + id;
        String name = "mcp-check-" + (1000 + (int) (Math.random() * 9000));

        post(chat, "{}");
        System.out.println("provider: " + post(chat + "/provider", "{\"provider\":\"" + provider + "\"}"));
        if (args.length > 3) System.out.println("model: " + post(chat + "/model", "{\"model\":\"" + args[3] + "\"}"));

        String q1 = "Use this conversation's set_chat_name tool to give conversation chat-" + id + " the name " + name
                + ". Then reply with the single word DONE.";
        turn(chat, q1);
        String project = get(base + "/api/projects/project1");
        check(project.contains("\"" + id + "\":\"" + name + "\""),
                "turn 1: set_chat_name ran through MCP and named the conversation", project);

        String sessions = get(base + "/api/sessions?conversationSquadId=" + conversation);
        Matcher m = Pattern.compile("\"sessionId\"\\s*:\\s*(\\d+)").matcher(sessions);
        String session = m.find() ? m.group(1) : null;
        String trace1 = session == null ? "" : get(base + "/api/sessions/" + session + "/trace/1");
        int calls = trace1.split("\"toolName\":\"set_chat_name\"", -1).length - 1;
        check(calls == 1, "turn 1: the call is recorded once, as the conversation's step (found " + calls + ")", trace1);
        check(!trace1.contains("Available tools") && !trace1.contains("<invoke"),
                "turn 1: no tool text was sent before the question", trace1.substring(0, Math.min(2000, trace1.length())));
        turn(chat, "Ask the person, with this conversation's ask_person tool, whether the report should be red or "
                + "blue. Do nothing else.");
        String conversationJson = get(chat + "/conversation");
        check(conversationJson.toLowerCase().contains("blue"), "turn 2: the turn ended with the question to the person",
                conversationJson.substring(Math.max(0, conversationJson.length() - 1500)));
        String trace2 = session == null ? "" : get(base + "/api/sessions/" + session + "/trace/2");
        check(trace2.contains("\"toolCalls\":\"ask_person") && !trace2.contains("turn2/step2/"),
                "turn 2: the step that called ask_person was the turn's last", trace2);

        turn(chat, "Reply with the single word THIRD.");
        conversationJson = get(chat + "/conversation");
        check(conversationJson.lastIndexOf("THIRD") > conversationJson.lastIndexOf("blue"),
                "turn 3: the CLI answered after the interrupted turn",
                conversationJson.substring(Math.max(0, conversationJson.length() - 1500)));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
