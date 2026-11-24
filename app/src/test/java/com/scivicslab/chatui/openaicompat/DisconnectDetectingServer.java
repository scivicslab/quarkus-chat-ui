package com.scivicslab.chatui.openaicompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test server that answers slowly and notices when the client closes the connection, by reading the socket until it
 * ends: the one proof that a cancel closed the connection (an HTTP library that merely stops reading leaves the socket open).
 *
 * <p>{@code /v1/models} is answered at once. Any other request is either held without an answer ({@link Mode#HOLD},
 * the headers never come) or answered with a stream that sends a small event every 50 ms for as long as the connection
 * lasts ({@link Mode#STREAM}). {@link #connectionClosed()} completes with the time, in {@code System.nanoTime()}, at
 * which the client's end of the held request was seen closed.</p>
 */
final class DisconnectDetectingServer implements AutoCloseable {

    enum Mode { HOLD, STREAM }

    private final ServerSocket socket;
    private final Mode mode;
    private final CompletableFuture<Void> requestSeen = new CompletableFuture<>();
    private final CompletableFuture<Void> firstEventSent = new CompletableFuture<>();
    private final CompletableFuture<Long> connectionClosed = new CompletableFuture<>();
    private final CopyOnWriteArrayList<String> requestLines = new CopyOnWriteArrayList<>();

    DisconnectDetectingServer(Mode mode) throws IOException {
        this.mode = mode;
        this.socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread.ofVirtual().start(this::acceptLoop);
    }

    String url() { return "http://127.0.0.1:" + socket.getLocalPort(); }

    CompletableFuture<Void> requestSeen() { return requestSeen; }

    CompletableFuture<Void> firstEventSent() { return firstEventSent; }

    CompletableFuture<Long> connectionClosed() { return connectionClosed; }

    /** The request lines (method and path) seen so far, in order. */
    java.util.List<String> requestLines() { return requestLines; }

    private void acceptLoop() {
        try {
            while (!socket.isClosed()) {
                Socket client = socket.accept();
                Thread.ofVirtual().start(() -> serve(client));
            }
        } catch (IOException ignored) {
            // closed
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String head = readHead(in);
            if (head == null) return;
            String requestLine = head.lines().findFirst().orElse("");
            requestLines.add(requestLine);
            int length = 0;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring("content-length:".length()).strip());
                }
            }
            in.readNBytes(length);
            if (requestLine.contains("/v1/models")) {
                byte[] body = "{\"data\":[{\"id\":\"m\",\"max_model_len\":32768}]}".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                        + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
                return;
            }
            requestSeen.complete(null);
            if (mode == Mode.STREAM) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.ofVirtual().start(() -> stream(out));
            }
            // The client sends nothing more: this read ends when the client closes the connection.
            while (in.read() >= 0) {
                // drain
            }
            connectionClosed.complete(System.nanoTime());
        } catch (IOException e) {
            connectionClosed.complete(System.nanoTime());
        }
    }

    private void stream(OutputStream out) {
        try {
            for (int i = 0; i < 1200; i++) {   // 60 s at most
                String event = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n";
                out.write((Integer.toHexString(event.length()) + "\r\n" + event + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                firstEventSent.complete(null);
                Thread.sleep(50);
            }
        } catch (IOException | InterruptedException e) {
            // the client went away
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            head.write(b);
            byte[] bytes = head.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') {
                return head.toString(StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
