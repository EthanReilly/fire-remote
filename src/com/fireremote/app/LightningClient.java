package com.fireremote.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Client for the Fire TV's built-in remote-control service (the one the official Amazon Fire TV
 * app uses). Pairing shows a 4-digit PIN on the TV; no developer options, ADB or prompt needed.
 *
 *  - DIAL wake-up:  POST http://TV:8009/apps/FireTVRemote   (starts the service, works while asleep)
 *  - Pairing:       POST https://TV:8080/v1/FireTV/pin/display {"friendlyName": ...}
 *                   POST https://TV:8080/v1/FireTV/pin/verify  {"pin": "1234"} -> {"description": token}
 *  - Keys:          POST https://TV:8080/v1/FireTV?action=dpad_up   (no body at all)
 *  - Media:         POST https://TV:8080/v1/media?action=play | scan
 *  - Apps:          POST https://TV:8080/v1/FireTV/app/<package>
 *
 * Uses one persistent TLS connection (HTTP/1.1 keep-alive), so key presses land in tens of ms.
 * Pure Java (no Android APIs) so it can be tested on a desktop JVM.
 */
final class LightningClient {

    static final String API_KEY = "0987654321";

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    /** The TV no longer accepts our token (e.g. after a reset); the user must pair again. */
    static final class NotPairedException extends IOException {
        NotPairedException() {
            super("Not paired");
        }
    }

    final String host;
    int httpsPort = 8080;
    int dialPort = 8009;
    private volatile String token;

    private final SSLSocketFactory tls;
    private SSLSocket conn; // guarded by this
    private long lastWake;

    LightningClient(String host, String token) {
        this.host = host;
        this.token = token;
        this.tls = trustAllFactory();
    }

    String token() {
        return token;
    }

    // ------------------------------------------------------------------ high level

    /** Starts the remote service on the TV (it stops when idle or asleep). Never throws. */
    boolean wake() {
        lastWake = System.currentTimeMillis();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, dialPort), 2000);
            s.setSoTimeout(2500);
            String req = "POST /apps/FireTVRemote HTTP/1.1\r\n"
                    + "Host: " + host + ":" + dialPort + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes("UTF-8"));
            out.flush();
            Response r = readResponse(s.getInputStream(), new boolean[1]);
            sleep(250); // let the service come up, as the official app does
            return r.code < 400;
        } catch (IOException e) {
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** Asks the TV to display a 4-digit PIN. */
    void showPin(String friendlyName) throws IOException {
        Response r = requestWithWake("POST", "/v1/FireTV/pin/display",
                "{\"friendlyName\":" + Json.quote(friendlyName) + "}", 6000, false);
        if (!r.ok()) throw new IOException("The Fire TV refused to show a PIN (HTTP " + r.code + ")");
    }

    /** Verifies the PIN typed by the user and stores the returned client token. Returns null if wrong. */
    String verifyPin(String pin) throws IOException {
        Response r = request("POST", "/v1/FireTV/pin/verify", "{\"pin\":" + Json.quote(pin) + "}", 6000, false);
        String t = r.ok() ? Json.stringField(r.body, "description") : null;
        if (t == null || t.length() == 0 || t.toLowerCase().contains("invalid")) return null;
        token = t;
        return t;
    }

    /** Remote button, e.g. "dpad_up", "select", "home", "back", "menu", "volume_up", "sleep". */
    Response key(String action) throws IOException {
        return command("POST", "/v1/FireTV?action=" + action, null);
    }

    Response media(String action, String json) throws IOException {
        return command("POST", "/v1/media?action=" + action, json);
    }

    Response launchApp(String pkg) throws IOException {
        return command("POST", "/v1/FireTV/app/" + pkg, null);
    }

    Response command(String method, String path, String json) throws IOException {
        Response r = requestWithWake(method, path, json, 3000, true);
        if (r.code == 401 || r.code == 403) throw new NotPairedException();
        return r;
    }

    // ------------------------------------------------------------------ transport

    /** Sends a request; if the service isn't answering, wakes it via DIAL and tries again. */
    private Response requestWithWake(String method, String path, String json, int timeoutMs, boolean auth)
            throws IOException {
        try {
            return request(method, path, json, timeoutMs, auth);
        } catch (IOException first) {
            if (first instanceof NotPairedException) throw first;
            wake();
            return request(method, path, json, timeoutMs, auth);
        }
    }

    synchronized Response request(String method, String path, String json, int timeoutMs, boolean auth)
            throws IOException {
        boolean reused = conn != null;
        try {
            return requestOnce(method, path, json, timeoutMs, auth);
        } catch (IOException e) {
            closeConn();
            if (!reused) throw e;
            // A kept-alive socket the TV had silently closed; retry on a fresh one.
            return requestOnce(method, path, json, timeoutMs, auth);
        }
    }

    private Response requestOnce(String method, String path, String json, int timeoutMs, boolean auth)
            throws IOException {
        if (conn == null) {
            SSLSocket s = (SSLSocket) tls.createSocket();
            s.connect(new InetSocketAddress(host, httpsPort), Math.min(timeoutMs, 3000));
            s.setTcpNoDelay(true);
            s.setSoTimeout(timeoutMs);
            s.startHandshake();
            conn = s;
        }
        conn.setSoTimeout(timeoutMs);
        byte[] body = json == null ? new byte[0] : json.getBytes("UTF-8");
        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(':').append(httpsPort).append("\r\n");
        req.append("x-api-key: ").append(API_KEY).append("\r\n");
        String t = token;
        if (auth && t != null && t.length() > 0) req.append("x-client-token: ").append(t).append("\r\n");
        req.append("User-Agent: okhttp/4.10.0\r\n");
        req.append("Accept-Encoding: identity\r\n");
        req.append("Connection: keep-alive\r\n");
        if (json != null) req.append("Content-Type: application/json; charset=utf-8\r\n");
        if (json != null || !"GET".equals(method)) req.append("Content-Length: ").append(body.length).append("\r\n");
        req.append("\r\n");
        OutputStream out = conn.getOutputStream();
        out.write(req.toString().getBytes("UTF-8"));
        if (body.length > 0) out.write(body);
        out.flush();
        boolean[] keepAlive = new boolean[1];
        Response r = readResponse(conn.getInputStream(), keepAlive);
        if (!keepAlive[0]) closeConn();
        return r;
    }

    synchronized void close() {
        closeConn();
    }

    private void closeConn() {
        if (conn != null) {
            try { conn.close(); } catch (IOException ignored) { }
            conn = null;
        }
    }

    // ------------------------------------------------------------------ minimal HTTP/1.1 parsing

    private static Response readResponse(InputStream in, boolean[] keepAliveOut) throws IOException {
        String status = readLine(in);
        if (status == null || !status.startsWith("HTTP/")) throw new IOException("Bad HTTP response");
        String[] parts = status.split(" ");
        if (parts.length < 2) throw new IOException("Bad HTTP status");
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad HTTP status");
        }
        boolean http11 = status.startsWith("HTTP/1.1");
        int contentLength = -1;
        boolean chunked = false;
        boolean keepAlive = http11;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String name = line.substring(0, c).trim().toLowerCase();
            String value = line.substring(c + 1).trim();
            if (name.equals("content-length")) {
                try { contentLength = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
            } else if (name.equals("transfer-encoding") && value.toLowerCase().contains("chunked")) {
                chunked = true;
            } else if (name.equals("connection")) {
                keepAlive = !value.equalsIgnoreCase("close");
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (code == 204 || code == 304 || (code >= 100 && code < 200)) {
            // no body
        } else if (chunked) {
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) throw new IOException("Truncated response");
                int semi = sizeLine.indexOf(';');
                int size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
                if (size == 0) {
                    while ((line = readLine(in)) != null && line.length() > 0) { /* trailers */ }
                    break;
                }
                copy(in, body, size);
                readLine(in);
            }
        } else if (contentLength >= 0) {
            copy(in, body, contentLength);
        } else {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
            keepAlive = false;
        }
        keepAliveOut[0] = keepAlive;
        return new Response(code, body.toString("UTF-8"));
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int n) throws IOException {
        byte[] buf = new byte[4096];
        while (n > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, n));
            if (r < 0) throw new IOException("Truncated response");
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("Header too long");
        }
        if (sb.length() == 0) throw new java.io.EOFException("Connection closed");
        return sb.toString();
    }

    private static SSLSocketFactory trustAllFactory() {
        // The Fire TV uses a self-signed certificate on the local network, like every client of this API.
        try {
            TrustManager[] tm = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** Tiny JSON helpers (org.json isn't on desktop JVMs; bodies here are trivial). */
    static final class Json {
        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.append('"').toString();
        }

        /** Returns the string value of the first "name": "value" pair, or null. */
        static String stringField(String json, String name) {
            if (json == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(json);
            if (!m.find()) return null;
            return m.group(1).replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\");
        }
    }
}
