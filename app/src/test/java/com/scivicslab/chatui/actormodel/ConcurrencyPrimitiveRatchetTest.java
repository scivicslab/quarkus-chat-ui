package com.scivicslab.chatui.actormodel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Ratchet over every hand-written concurrency primitive in {@code src/main} of all modules
 * (tasks/actor-model-conversion-plan.md, step 0). Each primitive is to be replaced by the POJO-actor
 * model, so the number of lines that use one may only fall.
 *
 * <p>The scan blanks comments, string literals, char literals and text blocks first, skips
 * {@code import} and {@code package} lines, then counts per file and per primitive the lines that
 * contain at least one match. The counts are compared with {@code concurrency-baseline.txt} (one line
 * per file and primitive: {@code path primitive count}):</p>
 * <ul>
 *   <li>a higher count, or any count in a file the baseline does not list, fails the test;</li>
 *   <li>a lower count fails the test with "lower the baseline", so the converted place cannot come back
 *       unnoticed;</li>
 *   <li>{@code mvn test -Dtest=ConcurrencyPrimitiveRatchetTest -Dconcurrency.baseline.write=true}
 *       rewrites the baseline, and refuses to when any count would rise.</li>
 * </ul>
 */
class ConcurrencyPrimitiveRatchetTest {

    private static final String BASELINE_RESOURCE = "concurrency-baseline.txt";

    /** Primitive name to the pattern that finds it in code with comments and literals blanked. */
    private static final Map<String, Pattern> PRIMITIVES = new TreeMap<>(Map.of(
            "parallel-stream", Pattern.compile("\\bparallelStream\\b|\\.parallel\\s*\\(\\s*\\)|\\bForkJoin\\w*"),
            "executor", Pattern.compile("\\bExecutors\\s*\\.|\\bExecutorService\\b|\\bScheduledExecutorService\\b"
                    + "|\\bThreadPoolExecutor\\b|\\bScheduledThreadPoolExecutor\\b"),
            "thread-start", Pattern.compile("\\bThread\\s*\\.\\s*(ofVirtual|ofPlatform|startVirtualThread)\\b"
                    + "|\\bnew\\s+Thread\\s*\\("),
            "synchronized", Pattern.compile("\\bsynchronized\\b"),
            "volatile", Pattern.compile("\\bvolatile\\b"),
            "concurrent-class", Pattern.compile("\\b(Atomic(Integer|Long|Boolean|Reference|IntegerArray|LongArray"
                    + "|ReferenceArray)|LongAdder|DoubleAdder|LongAccumulator|ConcurrentHashMap|ConcurrentSkipListMap"
                    + "|ConcurrentSkipListSet|ConcurrentLinkedQueue|ConcurrentLinkedDeque|CopyOnWriteArrayList"
                    + "|CopyOnWriteArraySet|LinkedBlockingQueue|LinkedBlockingDeque|ArrayBlockingQueue"
                    + "|PriorityBlockingQueue|SynchronousQueue|LinkedTransferQueue|BlockingQueue|BlockingDeque"
                    + "|ReentrantLock|ReentrantReadWriteLock|StampedLock|ReadWriteLock|Lock|Condition|Semaphore"
                    + "|CountDownLatch|CyclicBarrier|Phaser|Exchanger)\\b"),
            "sleep", Pattern.compile("\\bThread\\s*\\.\\s*sleep\\b|\\bTimeUnit\\s*\\.\\s*\\w+\\s*\\.\\s*sleep\\b")));

    /** The names of the calls that run on the common pool when they get no executor argument. */
    private static final Pattern ASYNC_CALL = Pattern.compile(
            "\\b(runAsync|supplyAsync|thenApplyAsync|thenAcceptAsync|thenRunAsync|thenComposeAsync"
                    + "|handleAsync|whenCompleteAsync)\\s*\\(");

    private static final String ASYNC_PRIMITIVE = "async-without-executor";

    // ------------------------------------------------------------------ scanning

    /** Blanks comments and string, char and text-block literals; keeps every line break. */
    static String blank(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && d == '/') {
                while (i < n && src.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && d == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) { out.append("  "); i += 2; }
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                out.append("   ");
                i += 3;
                while (i < n && !src.startsWith("\"\"\"", i)) {
                    if (src.charAt(i) == '\\' && i + 1 < n) { out.append("  "); i += 2; continue; }
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) { out.append("   "); i += 3; }
            } else if (c == '"' || c == '\'') {
                out.append(' ');
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\' && i + 1 < n) { out.append("  "); i += 2; continue; }
                    out.append(' ');
                    i++;
                }
                if (i < n && src.charAt(i) == c) { out.append(' '); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Per primitive, the number of lines of {@code src} that use it. */
    static Map<String, Integer> count(String src) {
        String code = blank(src);
        String[] lines = code.split("\n", -1);
        Map<String, Integer> counts = new TreeMap<>();
        for (String line : lines) {
            String t = line.stripLeading();
            if (t.startsWith("import ") || t.startsWith("package ")) continue;
            for (Map.Entry<String, Pattern> p : PRIMITIVES.entrySet()) {
                if (p.getValue().matcher(line).find()) counts.merge(p.getKey(), 1, Integer::sum);
            }
        }
        // An async call is counted on the line of its name, when its argument list has no executor.
        int[] lineStart = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) lineStart[i + 1] = lineStart[i] + lines[i].length() + 1;
        TreeSet<Integer> asyncLines = new TreeSet<>();
        Matcher m = ASYNC_CALL.matcher(code);
        while (m.find()) {
            if (topLevelArguments(code, m.end()) != 1) continue;
            int line = 0;
            while (line + 1 < lines.length && lineStart[line + 1] <= m.start()) line++;
            String t = lines[line].stripLeading();
            if (!t.startsWith("import ")) asyncLines.add(line);
        }
        if (!asyncLines.isEmpty()) counts.put(ASYNC_PRIMITIVE, asyncLines.size());
        return counts;
    }

    /** The number of top-level arguments of the call whose opening parenthesis ends at {@code from}. */
    private static int topLevelArguments(String code, int from) {
        int depth = 1, args = 0;
        boolean any = false;
        for (int i = from; i < code.length() && depth > 0; i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 1) args++;
            if (depth > 0 && !Character.isWhitespace(c) && !(c == ',' && depth == 1)) any = true;
        }
        return any ? args + 1 : 0;
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !(Files.isDirectory(dir.resolve("app")) && Files.isDirectory(dir.resolve("plugin-api")))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "the repository root (the directory with app and plugin-api) was not found");
        return dir;
    }

    /** Key {@code path primitive} to its count, over every {@code src/main/java} of every module. */
    private static Map<String, Integer> scanRepository(Path root) throws IOException {
        Map<String, Integer> all = new TreeMap<>();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.sorted().toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) continue;
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(sources::add);
                }
            }
        }
        for (Path file : sources) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            for (Map.Entry<String, Integer> e : count(Files.readString(file, StandardCharsets.UTF_8)).entrySet()) {
                all.put(rel + " " + e.getKey(), e.getValue());
            }
        }
        return all;
    }

    private static Path baselineFile(Path root) {
        return root.resolve("app/src/test/resources").resolve(BASELINE_RESOURCE);
    }

    private static Map<String, Integer> readBaseline(Path file) throws IOException {
        Map<String, Integer> base = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int last = line.lastIndexOf(' ');
            base.put(line.substring(0, last), Integer.parseInt(line.substring(last + 1).strip()));
        }
        return base;
    }

    // ------------------------------------------------------------------ the ratchet

    @Test
    void countsNeverRiseAboveTheBaselineAndTheBaselineNeverStaysAboveTheCounts() throws IOException {
        Path root = repositoryRoot();
        Path file = baselineFile(root);
        Map<String, Integer> actual = scanRepository(root);

        if (Boolean.getBoolean("concurrency.baseline.write")) {
            Map<String, Integer> old = Files.exists(file) ? readBaseline(file) : null;
            if (old != null) {
                for (Map.Entry<String, Integer> e : actual.entrySet()) {
                    if (e.getValue() > old.getOrDefault(e.getKey(), 0)) {
                        fail("refusing to write: the count of " + e.getKey() + " is " + e.getValue()
                                + ", above its baseline " + old.getOrDefault(e.getKey(), 0));
                    }
                }
            }
            StringBuilder sb = new StringBuilder("# path primitive count; may only shrink (ConcurrencyPrimitiveRatchetTest)\n");
            actual.forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return;
        }

        Map<String, Integer> base = readBaseline(file);
        List<String> raised = new ArrayList<>();
        List<String> lowered = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(actual.keySet());
        keys.addAll(base.keySet());
        for (String key : keys) {
            int a = actual.getOrDefault(key, 0), b = base.getOrDefault(key, 0);
            if (a > b) raised.add(key + ": " + a + " is above the baseline " + b);
            else if (a < b) lowered.add(key + ": " + a + " is below the baseline " + b);
        }
        StringBuilder msg = new StringBuilder();
        if (!raised.isEmpty()) {
            msg.append("New hand-written concurrency primitives; use the POJO-actor model instead:\n  ")
                    .append(String.join("\n  ", raised)).append('\n');
        }
        if (!lowered.isEmpty()) {
            msg.append("lower the baseline (rerun with -Dconcurrency.baseline.write=true and commit the file):\n  ")
                    .append(String.join("\n  ", lowered)).append('\n');
        }
        if (msg.length() > 0) fail(msg.toString());
    }

    // ------------------------------------------------------------------ the scanner itself

    @Test
    void commentsAndLiteralsAreIgnored() {
        String src = """
                // volatile int a; Thread.sleep(1)
                /* synchronized
                   parallelStream */
                class A {
                    String s = "volatile synchronized \\" Thread.sleep(3)";
                    String t = \"\"\"
                        ConcurrentHashMap Executors.newFixedThreadPool(1)
                        \"\"\";
                    char c = 'x';
                    volatile int real;
                }
                """;
        assertEquals(Map.of("volatile", 1), count(src));
    }

    @Test
    void eachPrimitiveIsRecognised() {
        String src = """
                import java.util.concurrent.ConcurrentHashMap;
                class A {
                    void f() throws Exception {
                        list.parallelStream().count();
                        Executors.newCachedThreadPool();
                        Thread.ofVirtual().start(r);
                        new Thread(r).start();
                        synchronized (this) { }
                        var m = new ConcurrentHashMap<String, String>();
                        var n = new AtomicInteger();
                        Thread.sleep(5);
                        CompletableFuture.runAsync(() -> {});
                        CompletableFuture.supplyAsync(() -> 1, pool);
                    }
                }
                """;
        Map<String, Integer> c = count(src);
        assertEquals(1, c.get("parallel-stream"));
        assertEquals(1, c.get("executor"));
        assertEquals(2, c.get("thread-start"));
        assertEquals(1, c.get("synchronized"));
        assertEquals(2, c.get("concurrent-class"));
        assertEquals(1, c.get("sleep"));
        assertEquals(1, c.get("async-without-executor"));
        assertEquals(7, c.size());
    }
}
