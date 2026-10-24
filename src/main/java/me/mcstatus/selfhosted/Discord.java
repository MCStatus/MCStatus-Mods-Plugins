package me.mcstatus.selfhosted;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Minimal Discord bot: REST calls plus a gateway connection that hands dispatch events to a callback. */
final class Discord implements WebSocket.Listener {

    private static final String API = "https://discord.com/api/v10";
    private static final URI GATEWAY = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");

    private final String token;
    private final int intents;
    private final BiConsumer<String, JsonObject> onEvent;
    private final Logger log;
    private final ScheduledExecutorService executor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final StringBuilder frame = new StringBuilder();
    private WebSocket ws;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
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
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordBot (https://mcstatus.me, 1.0)");
        if (body == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else if (files.isEmpty()) {
            req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            String boundary = "mcstatus" + System.nanoTime();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"
                    + body + "\r\n").getBytes(StandardCharsets.UTF_8));
            int i = 0;
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + i++ + "]\"; filename=\"" + file.getKey()
                        + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.writeBytes(file.getValue());
                out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            req.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        }

        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }

        if (res.statusCode() == 404) {
            throw new FileNotFoundException(method + " " + path + " returned 404");
        }
        if (res.statusCode() >= 400) {
            String text = res.body();
            throw new IOException("Discord returned HTTP " + res.statusCode() + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        return res.body().isEmpty() ? JsonNull.INSTANCE : JsonParser.parseString(res.body());
    }

    // ---- Gateway ----

    void connect() {
        if (closed) {
            return;
        }
        http.newWebSocketBuilder().buildAsync(GATEWAY, this).whenComplete((socket, err) -> {
            if (err != null) {
                log.warning("Could not connect to Discord: " + err.getMessage());
                reconnect(null);
            }
        });
    }

    void close() {
        closed = true;
        WebSocket socket;
        synchronized (this) {
            socket = ws;
            ws = null;
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(2, TimeUnit.SECONDS).exceptionally(e -> null).join();
        }
    }

    // ponytail: always re-IDENTIFY instead of RESUME; dispatches missed while disconnected are dropped
    private synchronized void reconnect(WebSocket from) {
        if (from != ws) {
            return; // stale callback from an old socket
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (ws != null) {
            ws.abort();
            ws = null;
        }
        if (!closed) {
            executor.schedule(this::connect, 5, TimeUnit.SECONDS);
        }
    }

    private synchronized void send(JsonObject payload) {
        WebSocket socket = ws;
        if (socket != null) {
            // WebSocket allows one outstanding send at a time, so queue them
            sendChain = sendChain.thenCompose(x -> socket.sendText(payload.toString(), true)).exceptionally(e -> null);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        synchronized (this) {
            ws = webSocket;
            frame.setLength(0);
        }
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (webSocket == ws) {
            frame.append(data);
            if (last) {
                String text = frame.toString();
                frame.setLength(0);
                try {
                    handle(webSocket, JsonParser.parseString(text).getAsJsonObject());
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Error handling a Discord gateway message", e);
                }
            }
        }
        webSocket.request(1);
        return null;
    }

    private void handle(WebSocket socket, JsonObject msg) {
        switch (msg.get("op").getAsInt()) {
            case 10 -> { // HELLO
                long interval = msg.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                acked = true;
                heartbeat = executor.scheduleAtFixedRate(() -> beat(socket), (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);

                JsonObject props = new JsonObject();
                props.addProperty("os", System.getProperty("os.name"));
                props.addProperty("browser", "mcstatus-selfhosted");
                props.addProperty("device", "mcstatus-selfhosted");
                JsonObject d = new JsonObject();
                d.addProperty("token", token);
                d.addProperty("intents", intents);
                d.add("properties", props);
                send(op(2, d));
            }
            case 11 -> acked = true;
            case 1 -> send(op(1, seq == null ? JsonNull.INSTANCE : new JsonPrimitive(seq)));
            case 7, 9 -> reconnect(socket); // RECONNECT, INVALID_SESSION
            case 0 -> {
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
            }
            default -> {}
        }
    }

    private void beat(WebSocket socket) {
        if (!acked) {
            log.warning("Discord stopped answering heartbeats, reconnecting");
            reconnect(socket);
            return;
        }
        acked = false;
        send(op(1, seq == null ? JsonNull.INSTANCE : new JsonPrimitive(seq)));
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
        switch (code) {
            case 4004 -> fatal("The bot token is invalid. Check bot-token in config.yml.");
            case 4014 -> fatal("The Message Content intent is not enabled. Enable it on the Bot page of the Discord developer portal, or set prefix to \"\" in config.yml.");
            case 4010, 4011, 4012, 4013 -> fatal("Discord closed the connection (" + code + " " + reason + ").");
            default -> {
                if (!closed) {
                    log.info("Discord connection closed (" + code + "), reconnecting");
                }
                reconnect(webSocket);
            }
        }
        return null;
    }

    private void fatal(String message) {
        log.severe(message);
        close();
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (!closed) {
            log.warning("Discord connection error: " + error.getMessage());
        }
        reconnect(webSocket);
    }

    private static JsonObject op(int op, JsonElement d) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.add("d", d);
        return o;
    }
}
