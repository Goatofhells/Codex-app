package dev.codex.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Lets static-musl Codex use Android's DNS via an authenticated local CONNECT tunnel.
 * TLS stays end-to-end between Codex and OpenAI; this bridge never decrypts it. */
final class AndroidNetworkBridge implements AutoCloseable {
    private final ServerSocket server;
    private final String token;
    private final String authorization;
    private final Predicate<String> allowedHost;
    private final int allowedPort;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Semaphore slots = new Semaphore(4);
    private final Set<Socket> active = ConcurrentHashMap.newKeySet();

    AndroidNetworkBridge() throws IOException {
        this(host -> host.equals("openai.com") || host.endsWith(".openai.com") ||
            host.equals("chatgpt.com") || host.endsWith(".chatgpt.com"), 443);
    }
    AndroidNetworkBridge(Predicate<String> allowedHost, int allowedPort) throws IOException {
        this.allowedHost = allowedHost;
        this.allowedPort = allowedPort;
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        authorization = "Basic " + Base64.getEncoder().encodeToString(("codex:" + token).getBytes(StandardCharsets.US_ASCII));
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        workers.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    if (!slots.tryAcquire()) { client.close(); continue; }
                    active.add(client);
                    workers.execute(() -> tunnel(client));
                } catch (IOException ignored) { break; }
            }
        });
    }
    String proxyUrl() { return "http://codex:" + token + "@127.0.0.1:" + server.getLocalPort(); }
    private void tunnel(Socket client) {
        Socket remote = new Socket();
        active.add(remote);
        try {
            client.setSoTimeout(15000);
            InputStream input = client.getInputStream();
            // Read exactly the header; BufferedReader could consume tunneled TLS bytes.
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int state = 0;
            while (header.size() < 16384 && state < 4) {
                int b = input.read();
                if (b < 0) return;
                header.write(b);
                int expected = state % 2 == 0 ? '\r' : '\n';
                state = b == expected ? state + 1 : (b == '\r' ? 1 : 0);
            }
            if (state != 4) return;
            String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
            String[] request = lines[0].split(" ");
            boolean authenticated = false;
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon > 0 && "proxy-authorization".equalsIgnoreCase(line.substring(0, colon)))
                    authenticated = authorization.equals(line.substring(colon + 1).trim());
            }
            if (!authenticated) { respond(client, "407 Proxy Authentication Required"); return; }
            if (request.length != 3 || !"CONNECT".equals(request[0])) { respond(client, "405 Method Not Allowed"); return; }
            String[] destination = request[1].split(":");
            if (destination.length != 2) { respond(client, "403 Forbidden"); return; }
            String host = destination[0].toLowerCase(Locale.ROOT);
            int port = Integer.parseInt(destination[1]);
            if (port != allowedPort || !allowedHost.test(host)) { respond(client, "403 Forbidden"); return; }
            remote.connect(new InetSocketAddress(host, port), 15000);
            remote.setSoTimeout(120000);
            client.setSoTimeout(120000);
            respond(client, "200 Connection Established");
            workers.execute(() -> {
                try { copy(input, remote.getOutputStream()); remote.shutdownOutput(); }
                catch (IOException ignored) { }
            });
            copy(remote.getInputStream(), client.getOutputStream());
        } catch (Exception ignored) { }
        finally {
            try { client.close(); } catch (IOException ignored) { }
            try { remote.close(); } catch (IOException ignored) { }
            active.remove(client);
            active.remove(remote);
            slots.release();
        }
    }
    private static void respond(Socket client, String status) throws IOException {
        client.getOutputStream().write(("HTTP/1.1 " + status + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }
    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); output.flush(); }
    }
    public void close() {
        try { server.close(); } catch (IOException ignored) { }
        active.forEach(socket -> { try { socket.close(); } catch (IOException ignored) { } });
        workers.shutdownNow();
    }
}
