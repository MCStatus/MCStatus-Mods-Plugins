package me.mcstatus.selfhosted;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Minimal Discord bot: REST calls plus a gateway WebSocket that hands dispatch events to a callback. */
final class Discord {

    private static final String API_HOST = "discord.com";
    private static final String GATEWAY_HOST = "gateway.discord.gg";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String token;
    private final int intents;
    private final BiConsumer<String, JsonObject> onEvent;
    private final Logger log;
    private final ScheduledExecutorService executor;

    private final Object writeLock = new Object();
    private SSLSocket socket; // current gateway connection, guarded by this
    private DataOutputStream out;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile boolean acked;
    private volatile Integer seq;
    private volatile boolean closed;

    Discord(String token, int intents, BiConsumer<String, JsonObject> onEvent, Logger log, ScheduledExecutorService executor) {
        this.token = token;
        this.intents = intents;
        this.onEvent = onEvent;
        this.log = log;
        this.executor = executor;
    }

    // ---- REST ----

    /** Sends a REST request. With {@code files}, the body goes as multipart payload_json plus files[n]. */
    JsonElement api(String method, String path, JsonElement body, Map<String, byte[]> files) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bot " + token);
        headers.put("User-Agent", "DiscordBot (https://mcstatus.me, 1.0)");

        byte[] payload = null;
        if (body != null && files.isEmpty()) {
            headers.put("Content-Type", "application/json");
            payload = body.toString().getBytes(StandardCharsets.UTF_8);
        } else if (body != null) {
            String boundary = "mcstatus" + System.nanoTime();
            ByteArrayOutputStream mp = new ByteArrayOutputStream();
            mp.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"
                    + body + "\r\n").getBytes(StandardCharsets.UTF_8));
            int i = 0;
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                mp.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + i++ + "]\"; filename=\"" + file.getKey()
                        + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                mp.write(file.getValue());
                mp.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            mp.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
            payload = mp.toByteArray();
        }

        Https.Response res = Https.request(method, API_HOST, "/api/v10" + path, headers, payload);
        String text = new String(res.body, StandardCharsets.UTF_8);
        if (res.status == 404) {
            throw new FileNotFoundException(method + " " + path + " returned 404");
        }
        if (res.status >= 400) {
            throw new IOException("Discord returned HTTP " + res.status + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        return text.isEmpty() ? JsonNull.INSTANCE : new JsonParser().parse(text);
    }

    // ---- Gateway ----

    void connect() {
        if (closed) {
            return;
        }
        Thread reader = new Thread(this::run, "MCStatus-Gateway");
        reader.setDaemon(true);
        reader.start();
    }

    /** One gateway connection: WebSocket handshake, then read frames until it drops. */
    private void run() {
        SSLSocket s = null;
        boolean current = false;
        try {
            s = Https.open(GATEWAY_HOST);
            byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            Map<String, String> headers = new HashMap<>();
            headers.put("Upgrade", "websocket");
            headers.put("Connection", "Upgrade");
            headers.put("Sec-WebSocket-Key", Base64.getEncoder().encodeToString(keyBytes));
            headers.put("Sec-WebSocket-Version", "13");
            Https.writeHead(s.getOutputStream(), "GET", GATEWAY_HOST, "/?v=10&encoding=json", headers);
            s.getOutputStream().flush();

            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            String status = Https.readHead(in).get(":status");
            if (!"101".equals(status)) {
                throw new IOException("gateway handshake returned HTTP " + status);
            }
            s.setSoTimeout(0); // heartbeat acks detect a dead connection
            synchronized (this) {
                if (closed) {
                    s.close();
                    return;
                }
                socket = s;
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                current = true;
            }

            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = in.readUnsignedShort();
                } else if (len == 127) {
                    len = in.readLong();
                }
                byte[] data = Https.readFully(in, (int) len);

                switch (b0 & 0x0F) {
                    case 0x0: // continuation
                    case 0x1: // text
                        message.write(data);
                        if ((b0 & 0x80) != 0) {
                            String text = new String(message.toByteArray(), StandardCharsets.UTF_8);
                            message.reset();
                            try {
                                handle(s, new JsonParser().parse(text).getAsJsonObject());
                            } catch (RuntimeException e) {
                                log.log(Level.WARNING, "Error handling a Discord gateway message", e);
                            }
                        }
                        break;
                    case 0x8: // close
                        onClose(s, data.length >= 2 ? ((data[0] & 0xFF) << 8) | (data[1] & 0xFF) : 1005,
                                data.length > 2 ? new String(data, 2, data.length - 2, StandardCharsets.UTF_8) : "");
                        return;
                    case 0x9: // ping
                        sendFrame(0xA, data);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            synchronized (this) {
                // closed on purpose, or reconnect() already replaced this connection
                if (closed || (current ? socket != s : socket != null)) {
                    return;
                }
            }
            log.warning("Discord connection error: " + e.getMessage());
            reconnect(current ? s : null);
        }
    }

    void close() {
        closed = true;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        SSLSocket s;
        synchronized (this) {
            s = socket;
            socket = null;
        }
        if (s != null) {
            try {
                sendFrame(0x8, new byte[]{0x03, (byte) 0xE8}, s); // 1000 normal closure
            } catch (IOException ignored) {
                // already gone
            }
            closeQuietly(s);
        }
    }

    // ponytail: always re-IDENTIFY instead of RESUME; dispatches missed while disconnected are dropped
    private synchronized void reconnect(SSLSocket from) {
        if (from != socket) {
            return; // stale callback from an old connection
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (socket != null) {
            closeQuietly(socket);
            socket = null;
        }
        if (!closed) {
            executor.schedule(this::connect, 5, TimeUnit.SECONDS);
        }
    }

    private void send(JsonObject payload) {
        try {
            sendFrame(0x1, payload.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warning("Could not send to Discord: " + e.getMessage());
        }
    }

    private void sendFrame(int opcode, byte[] data) throws IOException {
        SSLSocket s;
        synchronized (this) {
            s = socket;
        }
        if (s != null) {
            sendFrame(opcode, data, s);
        }
    }

    /** Writes one client frame; the WebSocket spec requires client frames to be masked. */
    private void sendFrame(int opcode, byte[] data, SSLSocket target) throws IOException {
        synchronized (writeLock) {
            DataOutputStream o;
            synchronized (this) {
                o = target == socket || socket == null ? out : null;
            }
            if (o == null) {
                return;
            }
            o.write(0x80 | opcode);
            if (data.length < 126) {
                o.write(0x80 | data.length);
            } else if (data.length < 65536) {
                o.write(0x80 | 126);
                o.writeShort(data.length);
            } else {
                o.write(0x80 | 127);
                o.writeLong(data.length);
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            o.write(mask);
            byte[] masked = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                masked[i] = (byte) (data[i] ^ mask[i & 3]);
            }
            o.write(masked);
            o.flush();
        }
    }

    private void handle(SSLSocket s, JsonObject msg) {
        switch (msg.get("op").getAsInt()) {
            case 10: { // HELLO
                long interval = msg.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                acked = true;
                heartbeat = executor.scheduleAtFixedRate(() -> beat(s), (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);

                JsonObject props = new JsonObject();
                props.addProperty("os", System.getProperty("os.name"));
                props.addProperty("browser", "mcstatus-selfhosted");
                props.addProperty("device", "mcstatus-selfhosted");
                JsonObject d = new JsonObject();
                d.addProperty("token", token);
                d.addProperty("intents", intents);
                d.add("properties", props);
                send(op(2, d));
                break;
            }
            case 11: // HEARTBEAT_ACK
                acked = true;
                break;
            case 1: // HEARTBEAT request
                send(op(1, seq == null ? JsonNull.INSTANCE : new JsonPrimitive(seq)));
                break;
            case 7: // RECONNECT
            case 9: // INVALID_SESSION
                reconnect(s);
                break;
            case 0: { // DISPATCH
                seq = msg.get("s").getAsInt();
                String type = msg.get("t").getAsString();
                JsonObject d = msg.getAsJsonObject("d");
                executor.execute(() -> {
                    try {
                        onEvent.accept(type, d);
                    } catch (Exception e) {
                        log.log(Level.WARNING, "Error handling Discord event " + type, e);
                    }
                });
                break;
            }
            default:
                break;
        }
    }

    private void beat(SSLSocket s) {
        if (!acked) {
            log.warning("Discord stopped answering heartbeats, reconnecting");
            reconnect(s);
            return;
        }
        acked = false;
        send(op(1, seq == null ? JsonNull.INSTANCE : new JsonPrimitive(seq)));
    }

    private void onClose(SSLSocket s, int code, String reason) {
        switch (code) {
            case 4004:
                fatal("The bot token is invalid. Check bot-token in config.yml.");
                break;
            case 4014:
                fatal("The Message Content intent is not enabled. Enable it on the Bot page of the Discord developer portal, or set prefix to \"\" in config.yml.");
                break;
            case 4010:
            case 4011:
            case 4012:
            case 4013:
                fatal("Discord closed the connection (" + code + " " + reason + ").");
                break;
            default:
                if (!closed) {
                    log.info("Discord connection closed (" + code + "), reconnecting");
                }
                reconnect(s);
        }
    }

    private void fatal(String message) {
        log.severe(message);
        close();
    }

    private static void closeQuietly(SSLSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static JsonObject op(int op, JsonElement d) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.add("d", d);
        return o;
    }
}
