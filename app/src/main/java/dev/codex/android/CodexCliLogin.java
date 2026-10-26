package dev.codex.android;

import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.*;
import static dev.codex.android.CodexConnection.json;

/** Runs the unmodified `codex login` flow used by the CLI, with no OAuth URL rewriting. */
final class CodexCliLogin implements AutoCloseable {
    interface Listener { void finished(String id, boolean success, String error); }
    private final Process process;
    private final Listener listener;
    private final String id=UUID.randomUUID().toString();
    private final CompletableFuture<JSONObject> started=new CompletableFuture<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private boolean finished;

    CodexCliLogin(Process process, Listener listener) {
        this.process=process; this.listener=listener;
    }
    CompletableFuture<JSONObject> start() {
        timer.schedule(()->{if(!started.isDone())finish(false,"Codex did not start browser sign-in within 30 seconds.");},30,TimeUnit.SECONDS);
        timer.schedule(()->finish(false,"Sign-in expired after 10 minutes. Start a new sign-in attempt."),10,TimeUnit.MINUTES);
        Thread reader=new Thread(()->{
            try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while((line=input.readLine())!=null) {
                    // Do not log CLI output: authorize URLs contain OAuth state and PKCE material.
                    String url=authorizeUrl(line);
                    if(url!=null)started.complete(json("type","chatgpt","loginId",id,"authUrl",url));
                }
                int exit=process.waitFor();
                finish(exit==0,exit==0?null:"Codex sign-in failed (exit "+exit+"). Try starting sign-in again.");
            } catch(Exception e) {finish(false,"The Codex sign-in process stopped unexpectedly.");}
        },"codex-cli-login");
        reader.setDaemon(true);reader.start();return started;
    }
    String id(){return id;}
    static String authorizeUrl(String line) {
        Matcher match=Pattern.compile("https://auth\\.openai\\.com/oauth/authorize\\?[^\\s\\u001b]+").matcher(line);
        if(!match.find())return null;
        String url=match.group();
        try {
            URI uri=URI.create(url);
            return "https".equals(uri.getScheme())&&"auth.openai.com".equals(uri.getHost())&&"/oauth/authorize".equals(uri.getPath())?url:null;
        } catch(IllegalArgumentException e) {return null;}
    }
    private void finish(boolean success,String error) {
        synchronized(this) {if(finished)return;finished=true;}
        timer.shutdownNow();
        if(!success)process.destroy();
        if(!started.isDone())started.completeExceptionally(new IOException(error==null?"Codex exited before providing a sign-in URL.":error));
        listener.finished(id,success,error);
    }
    public void close(){finish(false,"Sign-in cancelled.");}
}
