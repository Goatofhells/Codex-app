package dev.codex.android;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One private stdio connection. No shell, TCP listener, or detached daemon. */
public final class CodexConnection implements AutoCloseable {
    public interface Listener {
        void notification(String method, JSONObject params);
        void disconnected(String reason);
    }
    private final Process process;
    private final BufferedWriter writer;
    private final Listener listener;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;

    public CodexConnection(Process process, Listener listener) {
        this.process = process;
        this.listener = listener;
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        // Drain stderr separately: diagnostics must never corrupt the JSON stream or expose tokens.
        Thread errors = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] discard = new byte[4096];
                while (in.read(discard) != -1) { }
            } catch (IOException ignored) { }
        }, "codex-stderr");
        errors.setDaemon(true);
        errors.start();
        Thread reader = new Thread(this::read, "codex-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized CompletableFuture<JSONObject> request(String method, JSONObject params) {
        long id = ids.incrementAndGet();
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IOException("Codex stopped. Reopen the app to reconnect."));
            return result;
        }
        pending.put(id, result);
        ScheduledFuture<?> timeout = timer.schedule(() -> {
            CompletableFuture<JSONObject> old = pending.remove(id);
            if (old != null) old.completeExceptionally(new IOException(method + " timed out"));
        }, 60, TimeUnit.SECONDS);
        result.whenComplete((value, error) -> timeout.cancel(false));
        try { send(json("id", id, "method", method, "params", params)); }
        catch (Exception e) { pending.remove(id); result.completeExceptionally(e); }
        return result;
    }

    public void notifyInitialized() throws IOException { send(json("method", "initialized")); }

    private synchronized void send(JSONObject object) throws IOException {
        if (closed) throw new IOException("Codex stopped. Reopen the app to reconnect.");
        writer.write(object.toString());
        writer.newLine();
        writer.flush();
    }

    private void read() {
        String reason = "Codex process exited. Reopen the app to reconnect.";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject message = new JSONObject(line);
                if (message.has("method")) {
                    if (message.has("id")) {
                        // Milestone one is chat only. Never silently approve a tool or file operation.
                        String method = message.getString("method");
                        if (method.endsWith("/requestApproval"))
                            send(json("id", message.get("id"), "result", json("decision", "decline")));
                        else send(json("id", message.get("id"), "error", json("code", -32601, "message", "Unsupported client request")));
                    } else listener.notification(message.getString("method"), message.optJSONObject("params"));
                } else if (message.has("id")) {
                    CompletableFuture<JSONObject> result = pending.remove(message.getLong("id"));
                    if (result != null) {
                        if (message.has("error")) result.completeExceptionally(new IOException(message.getJSONObject("error").optString("message", "Codex request failed")));
                        else result.complete(message.optJSONObject("result"));
                    }
                }
            }
        } catch (Exception e) { reason = "Codex connection failed: " + e.getMessage(); }
        finally {
            boolean report = !closed;
            close();
            if (report) listener.disconnected(reason);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        process.destroy();
        pending.values().forEach(f -> f.completeExceptionally(new IOException("Codex connection closed")));
        pending.clear();
        timer.shutdownNow();
    }

    public static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
        return object;
    }
}
