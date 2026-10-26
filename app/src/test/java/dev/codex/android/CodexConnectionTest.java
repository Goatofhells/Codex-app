package dev.codex.android;

import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static dev.codex.android.CodexConnection.json;

public class CodexConnectionTest {
    static class FakeProcess extends Process {
        final PipedInputStream stdout = new PipedInputStream();
        final PipedOutputStream server;
        final PipedInputStream requests = new PipedInputStream();
        final PipedOutputStream stdin;
        final BufferedReader reader;
        FakeProcess() throws IOException {
            server = new PipedOutputStream(stdout);
            stdin = new PipedOutputStream(requests);
            reader = new BufferedReader(new InputStreamReader(requests));
        }
        void emit(JSONObject value) throws IOException {
            server.write((value.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            server.flush();
        }
        JSONObject request() throws Exception { return new JSONObject(reader.readLine()); }
        public OutputStream getOutputStream() { return stdin; }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return 0; }
        public int exitValue() { return 0; }
        public void destroy() { try { server.close(); stdin.close(); } catch (IOException ignored) { } }
    }
    static CodexConnection.Listener listener(BlockingQueue<String> events) {
        return new CodexConnection.Listener() {
            public void notification(String method, JSONObject params) { events.add(method + ":" + params.optString("delta")); }
            public void disconnected(String reason) { events.add("disconnected"); }
        };
    }
    @Test public void routesOutOfOrderResponsesAndStream() throws Exception {
        FakeProcess process = new FakeProcess();
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (CodexConnection client = new CodexConnection(process, listener(events))) {
            CompletableFuture<JSONObject> first = client.request("account/read", json());
            CompletableFuture<JSONObject> second = client.request("thread/start", json());
            long firstId = process.request().getLong("id");
            long secondId = process.request().getLong("id");
            process.emit(json("id", secondId, "result", json("thread", "test")));
            process.emit(json("method", "item/agentMessage/delta", "params", json("delta", "hello")));
            process.emit(json("id", firstId, "result", json("account", "test")));
            assertEquals("test", first.get(2, TimeUnit.SECONDS).getString("account"));
            assertEquals("test", second.get(2, TimeUnit.SECONDS).getString("thread"));
            assertEquals("item/agentMessage/delta:hello", events.poll(2, TimeUnit.SECONDS));
        }
    }
    @Test public void declinesApprovalAndRejectsUnknownRequests() throws Exception {
        FakeProcess process = new FakeProcess();
        try (CodexConnection client = new CodexConnection(process, listener(new LinkedBlockingQueue<>()))) {
            process.emit(json("id", "approval", "method", "item/commandExecution/requestApproval", "params", json()));
            assertEquals("decline", process.request().getJSONObject("result").getString("decision"));
            process.emit(json("id", 42, "method", "unknown", "params", json()));
            assertEquals(-32601, process.request().getJSONObject("error").getInt("code"));
        }
    }
    @Test public void propagatesErrorsAndFailsPendingOnExit() throws Exception {
        FakeProcess process = new FakeProcess();
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (CodexConnection client = new CodexConnection(process, listener(events))) {
            CompletableFuture<JSONObject> failed = client.request("login", json());
            process.emit(json("id", process.request().getLong("id"), "error", json("message", "Denied")));
            try { failed.get(2, TimeUnit.SECONDS); fail(); }
            catch (ExecutionException e) { assertEquals("Denied", e.getCause().getMessage()); }
            CompletableFuture<JSONObject> pending = client.request("pending", json());
            process.request();
            process.server.close();
            try { pending.get(2, TimeUnit.SECONDS); fail(); }
            catch (ExecutionException expected) { }
            assertEquals("disconnected", events.poll(2, TimeUnit.SECONDS));
        }
    }
}
