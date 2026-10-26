package dev.codex.android;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Map;
import java.util.concurrent.*;
import static dev.codex.android.CodexConnection.json;

/** Owned by the application so switching to the login browser keeps the process alive. */
public final class CodexSession extends Application {
    public interface Observer { void event(String method, JSONObject data); }
    volatile Observer observer;
    private CodexConnection connection;
    private AndroidNetworkBridge network;
    private String threadId;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private CompletableFuture<JSONObject> startup;
    private volatile Map<String,String> runtimeEnvironment;
    private CodexCliLogin activeLogin;
    private CompletableFuture<JSONObject> loginStart;

    public synchronized CompletableFuture<JSONObject> connect() {
        if (startup != null) return startup;
        startup = CompletableFuture.supplyAsync(() -> {
            File executable = new File(getApplicationInfo().nativeLibraryDir, "libcodex.so");
            if (!executable.isFile()) throw new CompletionException(new IllegalStateException(
                "Codex runtime is not bundled for this device. Run scripts/fetch-runtime.py before building. See README."));
            File home = new File(getFilesDir(), "codex");
            File workspace = new File(getFilesDir(), "workspace");
            if ((!home.isDirectory() && !home.mkdirs()) || (!workspace.isDirectory() && !workspace.mkdirs()))
                throw new CompletionException(new IllegalStateException("Cannot create private storage"));
            try {
                ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "app-server");
                builder.directory(workspace);
                builder.environment().put("CODEX_HOME", home.getAbsolutePath());
                builder.environment().put("HOME", getFilesDir().getAbsolutePath());
                builder.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                // Static musl expects /etc/resolv.conf, which Android does not provide.
                network = new AndroidNetworkBridge();
                builder.environment().put("HTTPS_PROXY", network.proxyUrl());
                builder.environment().put("HTTP_PROXY", network.proxyUrl());
                builder.environment().put("NO_PROXY", "localhost,127.0.0.1,::1");
                File certificates = new File("/apex/com.android.conscrypt/cacerts");
                if (!certificates.isDirectory()) certificates = new File("/system/etc/security/cacerts");
                builder.environment().put("SSL_CERT_DIR", certificates.getAbsolutePath());
                runtimeEnvironment = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(builder.environment()));
                connection = new CodexConnection(builder.start(), new CodexConnection.Listener() {
                    public void notification(String method, JSONObject data) { emit(method, data); }
                    public void disconnected(String reason) { if (network != null) network.close(); emit("disconnected", json("message", reason)); }
                });
                connection.request("initialize", json("clientInfo", json("name", "codex_android", "title", "Codex Android", "version", BuildConfig.VERSION_NAME))).get(65, TimeUnit.SECONDS);
                connection.notifyInitialized();
                return connection.request("account/read", json("refreshToken", false)).get(65, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (connection != null) connection.close();
                if (network != null) network.close();
                throw new CompletionException(e);
            }
        }, worker);
        return startup;
    }

    void emit(String method, JSONObject data) {
        Observer current = observer;
        if (current != null) current.event(method, data == null ? json() : data);
    }
    public CompletableFuture<JSONObject> account() { return connection.request("account/read", json("refreshToken", false)); }
    public synchronized CompletableFuture<JSONObject> login() {
        if (loginStart != null) return loginStart;
        if (runtimeEnvironment == null) return failed(new IllegalStateException("Connect to Codex before signing in."));
        try { LoginKeepAliveService.begin(this); }
        catch (RuntimeException e) { return failed(new IllegalStateException("Android could not keep the browser sign-in session alive.", e)); }
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        loginStart = result;
        worker.execute(() -> {
            try {
                File executable = new File(getApplicationInfo().nativeLibraryDir, "libcodex.so");
                ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "login");
                builder.directory(new File(getFilesDir(), "workspace"));
                builder.environment().clear(); builder.environment().putAll(runtimeEnvironment);
                builder.redirectErrorStream(true);
                CodexCliLogin attempt = new CodexCliLogin(builder.start(), this::loginFinished);
                synchronized (this) { activeLogin = attempt; }
                attempt.start().whenComplete((value,error) -> {
                    if (error != null) result.completeExceptionally(error); else result.complete(value);
                });
            } catch (Exception e) {
                synchronized (this) { loginStart = null; }
                LoginKeepAliveService.end(this);
                result.completeExceptionally(e);
            }
        });
        return result;
    }
    private void loginFinished(String id, boolean success, String error) {
        synchronized (this) {
            if (activeLogin == null || !activeLogin.id().equals(id)) return;
            activeLogin = null; loginStart = null;
        }
        if (!success) {
            LoginKeepAliveService.end(this);
            emit("account/login/completed", json("loginId",id,"success",false,"error",error));
            return;
        }
        // The CLI saved credentials in the same CODEX_HOME. Restart to discard cached auth.
        restart().whenComplete((account,failure) -> {
            LoginKeepAliveService.end(this);
            boolean signedIn = failure == null && account != null && account.optJSONObject("account") != null;
            emit("account/login/completed",json("loginId",id,"success",signedIn,"error",signedIn?JSONObject.NULL:"Browser sign-in ended, but Codex could not reload the saved account. Tap the runtime status to retry."));
        });
    }
    public synchronized CompletableFuture<JSONObject> cancelLogin(String id) {
        if (activeLogin != null && activeLogin.id().equals(id)) activeLogin.close();
        return CompletableFuture.completedFuture(json());
    }
    public synchronized void cancelActiveLogin() { if (activeLogin != null) activeLogin.close(); }
    public synchronized CompletableFuture<JSONObject> pendingLogin() { return loginStart; }
    private static CompletableFuture<JSONObject> failed(Exception error) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        result.completeExceptionally(error); return result;
    }
    public CompletableFuture<JSONObject> logout() {
        threadId = null;
        return connection.request("account/logout", json());
    }
    public synchronized void newChat() { threadId = null; }
    public synchronized CompletableFuture<JSONObject> restart() {
        if (connection != null) connection.close();
        if (network != null) network.close();
        startup = null; threadId = null;
        return connect();
    }
    public CompletableFuture<JSONObject> send(String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (threadId == null) {
                    String mode = getSharedPreferences("agent", 0).getString("mode", "workspaceWrite");
                    JSONObject started = connection.request("thread/start", json("cwd", new File(getFilesDir(), "workspace").getAbsolutePath(), "approvalPolicy", "on-request", "sandbox", mode)).get(65, TimeUnit.SECONDS);
                    threadId = started.getJSONObject("thread").getString("id");
                }
                return connection.request("turn/start", json("threadId", threadId, "input", new JSONArray().put(json("type", "text", "text", text)))).get(65, TimeUnit.SECONDS);
            } catch (Exception e) { throw new CompletionException(e); }
        }, worker);
    }
}
