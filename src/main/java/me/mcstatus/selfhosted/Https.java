package me.mcstatus.selfhosted;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Bare HTTP/1.1 over TLS. Java 8 (Minecraft 1.16) has no WebSocket client and HttpURLConnection
 * can't send PATCH, so the bot speaks HTTP itself: one request per connection, plus the
 * WebSocket upgrade handshake.
 */
final class Https {

    static final class Response {
        final int status;
        final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    private Https() {}

    static SSLSocket open(String host) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, 443), 10_000);
        SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, 443, true);
        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS"); // verify the certificate matches the host
        socket.setSSLParameters(params);
        socket.setSoTimeout(10_000);
        socket.startHandshake();
        return socket;
    }

    // ponytail: new TLS connection per request; a few requests a minute doesn't need keep-alive
    static Response request(String method, String host, String path, Map<String, String> headers, byte[] body) throws IOException {
        try (SSLSocket socket = open(host)) {
            Map<String, String> all = new HashMap<>(headers);
            all.put("Connection", "close");
            all.put("Content-Length", String.valueOf(body == null ? 0 : body.length));
            writeHead(socket.getOutputStream(), method, host, path, all);
            if (body != null) {
                socket.getOutputStream().write(body);
            }
            socket.getOutputStream().flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            Map<String, String> head = readHead(in);
            int status = Integer.parseInt(head.get(":status"));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if ("chunked".equalsIgnoreCase(head.get("transfer-encoding"))) {
                for (int size; (size = Integer.parseInt(readLine(in).split(";")[0].trim(), 16)) > 0; readLine(in)) {
                    out.write(readFully(in, size));
                }
            } else if (head.containsKey("content-length")) {
                out.write(readFully(in, Integer.parseInt(head.get("content-length"))));
            } else {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) != -1; ) {
                    out.write(buf, 0, n);
                }
            }
            return new Response(status, out.toByteArray());
        }
    }

    static void writeHead(OutputStream out, String method, String host, String path, Map<String, String> headers) throws IOException {
        StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ").append(host).append("\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        out.write(sb.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Status line and headers; header names are lowercased and the status code is under ":status". */
    static Map<String, String> readHead(InputStream in) throws IOException {
        Map<String, String> head = new HashMap<>();
        String status = readLine(in); // HTTP/1.1 200 OK
        head.put(":status", status.split(" ")[1]);
        for (String line; !(line = readLine(in)).isEmpty(); ) {
            int colon = line.indexOf(':');
            head.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        return head;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != '\n'; ) {
            if (b == -1) {
                throw new EOFException("Connection closed");
            }
            line.write(b);
        }
        String s = new String(line.toByteArray(), StandardCharsets.UTF_8);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        new DataInputStream(in).readFully(data);
        return data;
    }
}
