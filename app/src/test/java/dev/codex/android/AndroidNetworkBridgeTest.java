package dev.codex.android;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AndroidNetworkBridgeTest {
    private String header(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = input.read();
            if (b == -1) throw new EOFException();
            bytes.write(b);
            state = b == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : 0;
        }
        return bytes.toString("US-ASCII");
    }
    private Socket connect(AndroidNetworkBridge bridge, String target, boolean auth) throws Exception {
        URI uri = URI.create(bridge.proxyUrl());
        Socket socket = new Socket(uri.getHost(), uri.getPort());
        socket.setSoTimeout(3000);
        String credentials = Base64.getEncoder().encodeToString(uri.getUserInfo().getBytes(StandardCharsets.US_ASCII));
        String request = "CONNECT " + target + " HTTP/1.1\r\n" +
            (auth ? "Proxy-Authorization: Basic " + credentials + "\r\n" : "") + "\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }
    @Test public void tunnelsBytesWithoutDecrypting() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket echo = new ServerSocket(0);
             AndroidNetworkBridge bridge = new AndroidNetworkBridge(host -> host.equals("localhost"), echo.getLocalPort())) {
            Future<?> server = worker.submit(() -> {
                try (Socket client = echo.accept()) {
                    byte[] data = new byte[4];
                    new DataInputStream(client.getInputStream()).readFully(data);
                    client.getOutputStream().write(data);
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            try (Socket socket = connect(bridge, "localhost:" + echo.getLocalPort(), true)) {
                assertTrue(header(socket.getInputStream()).startsWith("HTTP/1.1 200"));
                byte[] bytes = { 22, 3, 1, 0 };
                socket.getOutputStream().write(bytes);
                socket.getOutputStream().flush();
                byte[] received = new byte[4];
                new DataInputStream(socket.getInputStream()).readFully(received);
                assertArrayEquals(bytes, received);
            }
            server.get(3, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
    @Test public void refusesMissingCredentialsAndUnapprovedHosts() throws Exception {
        try (AndroidNetworkBridge bridge = new AndroidNetworkBridge()) {
            try (Socket socket = connect(bridge, "auth.openai.com:443", false)) {
                assertTrue(header(socket.getInputStream()).startsWith("HTTP/1.1 407"));
            }
            try (Socket socket = connect(bridge, "example.com:443", true)) {
                assertTrue(header(socket.getInputStream()).startsWith("HTTP/1.1 403"));
            }
        }
    }
}
