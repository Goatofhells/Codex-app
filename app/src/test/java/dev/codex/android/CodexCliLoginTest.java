package dev.codex.android;

import org.junit.Test;
import org.junit.Assume;
import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CodexCliLoginTest {
    @Test public void bundledAppServerStillSupportsAccountHandshake() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").equals("Linux")&&System.getProperty("os.arch").equals("amd64"));
        Path binary=Path.of("src/main/jniLibs/x86_64/libcodex.so").toAbsolutePath();
        Path home=Files.createTempDirectory("codex-account-test-");
        ProcessBuilder builder=new ProcessBuilder(binary.toString(),"app-server");
        builder.environment().put("CODEX_HOME",home.toString());
        try(CodexConnection client=new CodexConnection(builder.start(),new CodexConnection.Listener(){
            public void notification(String method,JSONObject params){}
            public void disconnected(String reason){}
        })) {
            client.request("initialize",CodexConnection.json("clientInfo",CodexConnection.json("name","codex_android","version","0.1.1"))).get(20,TimeUnit.SECONDS);
            client.notifyInitialized();
            JSONObject account=client.request("account/read",CodexConnection.json("refreshToken",false)).get(20,TimeUnit.SECONDS);
            assertTrue(account.isNull("account"));assertTrue(account.getBoolean("requiresOpenaiAuth"));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(home)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});
            }
        }
    }
    @Test public void preservesTheCompleteCliAuthorizationUrl() {
        String url="https://auth.openai.com/oauth/authorize?originator=codex_cli_rs&state=sample%2Bstate&code_challenge=sample_challenge&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback";
        assertEquals(url,CodexCliLogin.authorizeUrl("Open this URL in your browser: "+url));
        assertNull(CodexCliLogin.authorizeUrl("https://auth.openai.com.evil.example/oauth/authorize?x=1"));
        assertNull(CodexCliLogin.authorizeUrl("http://auth.openai.com/oauth/authorize?x=1"));
    }
    @Test public void cancellationCompletesOnlyOnce() throws Exception {
        CodexConnectionTest.FakeProcess process=new CodexConnectionTest.FakeProcess();
        AtomicInteger completions=new AtomicInteger();
        CodexCliLogin login=new CodexCliLogin(process,(id,success,error)->{assertFalse(success);completions.incrementAndGet();});
        CompletableFuture<JSONObject> started=login.start();
        login.close();login.close();
        try {started.get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
        assertEquals(1,completions.get());
    }
    @Test public void forwardsUrlAndReportsSuccessfulExit() throws Exception {
        String url="https://auth.openai.com/oauth/authorize?state=example&code_challenge=example";
        CodexConnectionTest.FakeProcess process=new CodexConnectionTest.FakeProcess();
        CountDownLatch finished=new CountDownLatch(1);
        try(CodexCliLogin login=new CodexCliLogin(process,(id,success,error)->{assertTrue(success);finished.countDown();})) {
            CompletableFuture<JSONObject> started=login.start();
            process.server.write(("Starting local login server\n"+url+"\n").getBytes(StandardCharsets.UTF_8));process.server.flush();
            assertEquals(url,started.get(2,TimeUnit.SECONDS).getString("authUrl"));
            process.server.close();assertTrue(finished.await(2,TimeUnit.SECONDS));
        }
    }
    @Test public void bundledRuntimeUsesCliOriginatorAndIpv4Callback() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").equals("Linux")&&System.getProperty("os.arch").equals("amd64"));
        Path binary=Path.of("src/main/jniLibs/x86_64/libcodex.so").toAbsolutePath();
        assertTrue("Fetch the pinned Codex runtime before running integration tests",Files.isExecutable(binary));
        Path home=Files.createTempDirectory("codex-login-test-");
        ProcessBuilder builder=new ProcessBuilder(binary.toString(),"login").redirectErrorStream(true);
        builder.environment().put("CODEX_HOME",home.toString());
        builder.environment().put("BROWSER","/bin/true");
        try(CodexCliLogin login=new CodexCliLogin(builder.start(),(id,success,error)->{})) {
            URI url=URI.create(login.start().get(20,TimeUnit.SECONDS).getString("authUrl"));
            assertEquals("auth.openai.com",url.getHost());
            String query=url.getRawQuery();
            assertTrue(query.contains("originator=codex_cli_rs"));
            assertTrue(query.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"));
            assertTrue(query.contains("code_challenge="));assertTrue(query.contains("state="));
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(home)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});
            }
        }
    }
}
