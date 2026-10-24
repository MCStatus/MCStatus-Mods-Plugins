package me.mcstatus.selfhosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Platform-independent core: config, /status, prefix command, and the live update message. */
public final class MCStatus {

    private static final String DEFAULT_ICON = "https://cdn.mcstatus.me/default.png";
    private static final int MAX_SERVERS = 5;
    private static final String COMMAND_NAME = "[-_\\p{L}\\p{N}]{1,32}"; // Discord's rule for command/option names

    /** {@code maxPlayers} is -1 when not set. */
    record Server(String name, String host, int port, String footer, String website, String icon, String version, int maxPlayers) {}

    private final Logger log;
    private final String guildId;
    private final String prefix;
    private final String channelId;
    private final int interval;
    private final List<Server> servers;
    private final Map<String, Object> panel;
    private final Map<String, Object> messages;
    private final String command;
    private final int onlineColor;
    private final int offlineColor;
    private final Map<Server, Ping.Status> lastSeen = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "MCStatus");
        t.setDaemon(true);
        return t;
    });
    private final Discord discord;

    private volatile String selfId;
    private volatile String appId;
    private volatile String messageId;
    private final Object sendLock = new Object();

    /**
     * Loads {@code dir/config.yml} (writing the default on first run) and connects the bot.
     * Returns null when the bot token has not been set yet.
     *
     * @param selfPort port of this server/proxy, used for servers whose address is left empty
     */
    public static MCStatus start(Path dir, int selfPort, Logger log) {
        Path file = dir.resolve("config.yml");
        Map<String, Object> config;
        Map<String, Object> defaults;
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(dir);
                try (InputStream in = MCStatus.class.getResourceAsStream("/config.yml")) {
                    Files.copy(in, file);
                }
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = new Yaml().load(reader);
            }
            try (Reader reader = new InputStreamReader(MCStatus.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
                defaults = new Yaml().load(reader);
            }
        } catch (IOException | RuntimeException e) {
            log.log(Level.SEVERE, "Could not load " + file, e);
            return null;
        }

        if (str(config, "bot-token").isEmpty()) {
            log.warning("Set bot-token in " + file.toAbsolutePath() + " and restart the server to start MCStatus.");
            return null;
        }

        try {
            MCStatus mc = new MCStatus(config, defaults, selfPort, log);
            log.info("Starting MCStatus with " + mc.servers.size() + " server(s)");
            return mc;
        } catch (IllegalArgumentException e) {
            log.severe("Invalid " + file.toAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    private MCStatus(Map<String, Object> config, Map<String, Object> defaults, int selfPort, Logger log) {
        this.log = log;
        this.guildId = str(config, "discord-server-id");
        this.prefix = str(config, "prefix");
        this.channelId = str(config, "live-update-channel-id");
        this.interval = Math.max(10, config.get("live-update-interval") instanceof Number n ? n.intValue() : 30);
        this.servers = parseServers(config.get("servers"), selfPort);

        // Keys missing from an older config.yml fall back to the defaults bundled in the jar
        this.panel = section(defaults, config, "panel");
        this.messages = section(defaults, config, "messages");
        this.command = str(messages, "command-name").toLowerCase();
        if (!command.matches(COMMAND_NAME) || !str(messages, "server-option-name").matches(COMMAND_NAME)) {
            throw new IllegalArgumentException("command-name and server-option-name must be 1-32 letters, numbers, - or _ (no spaces)");
        }
        this.onlineColor = color("online-color");
        this.offlineColor = color("offline-color");

        // GUILD_MESSAGES | MESSAGE_CONTENT, only needed for the text prefix
        int intents = prefix.isEmpty() ? 0 : (1 << 9) | (1 << 15);
        discord = new Discord(str(config, "bot-token"), intents, this::onEvent, log, executor);
        discord.connect();
    }

    private List<Server> parseServers(Object raw, int selfPort) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("add at least one entry under servers:");
        }
        if (list.size() > MAX_SERVERS) {
            log.warning("Only the first " + MAX_SERVERS + " servers in config.yml are used");
        }

        List<Server> result = new ArrayList<>();
        for (Object o : list.subList(0, Math.min(list.size(), MAX_SERVERS))) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("each entry under servers: needs name: and address:");
            }
            @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) m;

            String name = str(s, "name");
            if (name.isEmpty()) {
                name = "Minecraft Server";
            }
            for (Server other : result) {
                if (other.name().equalsIgnoreCase(name)) {
                    throw new IllegalArgumentException("two servers are named \"" + name + "\"");
                }
            }

            String address = str(s, "address");
            String host = "127.0.0.1";
            int port = selfPort;
            if (!address.isEmpty()) {
                int colon = address.lastIndexOf(':');
                host = colon < 0 ? address : address.substring(0, colon);
                try {
                    port = colon < 0 ? 25565 : Integer.parseInt(address.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad port in address \"" + address + "\"");
                }
            }

            int max = s.get("max-players") instanceof Number n ? n.intValue() : -1;
            result.add(new Server(name, host, port, str(s, "footer"), str(s, "website"), str(s, "icon-url"), str(s, "version"), max));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> defaults, Map<String, Object> config, String key) {
        Map<String, Object> merged = new HashMap<>((Map<String, Object>) defaults.get(key));
        if (config.get(key) instanceof Map<?, ?> user) {
            merged.putAll((Map<String, Object>) user);
        }
        return merged;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** Text for an embed field name/value, which Discord rejects when empty. */
    private String label(String key) {
        String s = str(panel, key);
        return s.isEmpty() ? "​" : s;
    }

    private boolean show(String key) {
        return !Boolean.FALSE.equals(panel.get(key));
    }

    private int color(String key) {
        String hex = str(panel, key).replace("#", "");
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a hex color like \"#6C9B47\"");
        }
    }

    /** Disconnects and posts the offline panel. Blocks until any live update already being sent is done. */
    public void stop() {
        discord.close();
        // No interrupt: an edit already on its way to Discord could otherwise land after the offline one
        executor.shutdown();

        synchronized (sendLock) {
            if (messageId == null) {
                return;
            }
            Map<String, byte[]> files = new LinkedHashMap<>();
            JsonArray embeds = new JsonArray();
            for (Server s : servers) {
                embeds.add(embed(s, null, files));
            }
            try {
                discord.api("PATCH", "/channels/" + channelId + "/messages/" + messageId, body(embeds, files), files);
            } catch (IOException e) {
                log.warning("Could not post the offline status to Discord: " + e.getMessage());
            }
        }
    }

    // ---- Discord events ----

    private void onEvent(String type, JsonObject d) {
        try {
            switch (type) {
                case "READY" -> onReady(d);
                case "INTERACTION_CREATE" -> onInteraction(d);
                case "MESSAGE_CREATE" -> onMessage(d);
                default -> {}
            }
        } catch (IOException e) {
            log.warning("Discord " + type + " failed: " + e.getMessage());
        }
    }

    private synchronized void onReady(JsonObject d) throws IOException {
        if (appId != null) {
            return; // reconnect, already set up
        }
        selfId = d.getAsJsonObject("user").get("id").getAsString();
        appId = d.getAsJsonObject("application").get("id").getAsString();
        log.info("Logged in to Discord as " + d.getAsJsonObject("user").get("username").getAsString());
        if (!channelId.isEmpty()) {
            executor.scheduleWithFixedDelay(this::liveUpdate, 0, interval, TimeUnit.SECONDS);
        }

        JsonObject status = new JsonObject();
        status.addProperty("name", command);
        status.addProperty("description", str(messages, "command-description"));
        if (servers.size() > 1) {
            JsonObject option = new JsonObject();
            option.addProperty("name", str(messages, "server-option-name").toLowerCase());
            option.addProperty("description", str(messages, "server-option-description"));
            option.addProperty("type", 3); // string
            option.addProperty("required", true);
            JsonArray choices = new JsonArray();
            for (Server s : servers) {
                JsonObject choice = new JsonObject();
                choice.addProperty("name", s.name());
                choice.addProperty("value", s.name());
                choices.add(choice);
            }
            option.add("choices", choices);
            JsonArray options = new JsonArray();
            options.add(option);
            status.add("options", options);
        }
        JsonArray commands = new JsonArray();
        commands.add(status);
        discord.api("PUT", "/applications/" + appId + (guildId.isEmpty() ? "" : "/guilds/" + guildId) + "/commands", commands, Map.of());
    }

    private void onInteraction(JsonObject d) throws IOException {
        JsonObject data = d.getAsJsonObject("data");
        if (d.get("type").getAsInt() != 2 || !command.equals(data.get("name").getAsString())) {
            return;
        }
        String name = data.has("options") ? data.getAsJsonArray("options").get(0).getAsJsonObject().get("value").getAsString() : "";

        // Defer first: pinging can take longer than the 3 seconds Discord gives us
        JsonObject defer = new JsonObject();
        defer.addProperty("type", 5);
        String token = d.get("token").getAsString();
        discord.api("POST", "/interactions/" + d.get("id").getAsString() + "/" + token + "/callback", defer, Map.of());

        Map<String, byte[]> files = new LinkedHashMap<>();
        discord.api("PATCH", "/webhooks/" + appId + "/" + token + "/messages/@original", statusReply(name, files), files);
    }

    private void onMessage(JsonObject d) throws IOException {
        String content = d.get("content").getAsString();
        String trigger = prefix + command;
        if (prefix.isEmpty() || d.getAsJsonObject("author").get("id").getAsString().equals(selfId)
                || !content.regionMatches(true, 0, trigger, 0, trigger.length())) {
            return;
        }
        String rest = content.substring(trigger.length());
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return; // e.g. "!statusbar"
        }

        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject reply = statusReply(rest.trim(), files);
        JsonObject ref = new JsonObject();
        ref.addProperty("message_id", d.get("id").getAsString());
        reply.add("message_reference", ref);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        mentions.addProperty("replied_user", false);
        reply.add("allowed_mentions", mentions);
        discord.api("POST", "/channels/" + d.get("channel_id").getAsString() + "/messages", reply, files);
    }

    /** The /status reply body: the named server's panel, or the first server when no name is given. */
    private JsonObject statusReply(String name, Map<String, byte[]> files) {
        Server server = find(servers, name);
        JsonArray embeds = new JsonArray();
        if (server == null) {
            JsonObject e = new JsonObject();
            e.addProperty("description", str(messages, "unknown-server")
                    .replace("%name%", name.replace("`", ""))
                    .replace("%servers%", servers.stream().map(Server::name).collect(Collectors.joining(", "))));
            e.addProperty("color", offlineColor);
            embeds.add(e);
        } else {
            embeds.add(embed(server, ping(server), files));
        }
        return body(embeds, files);
    }

    static Server find(List<Server> servers, String name) {
        if (name.isEmpty()) {
            return servers.get(0);
        }
        for (Server s : servers) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    // ---- Live update ----

    private void liveUpdate() {
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            JsonArray embeds = new JsonArray();
            for (Server s : servers) {
                embeds.add(embed(s, ping(s), files));
            }
            JsonObject body = body(embeds, files);

            synchronized (sendLock) {
                if (executor.isShutdown()) {
                    return; // stopping: the offline panel is the last edit
                }
                if (messageId == null) {
                    messageId = findOwnMessage();
                }
                if (messageId != null) {
                    try {
                        discord.api("PATCH", "/channels/" + channelId + "/messages/" + messageId, body, files);
                        return;
                    } catch (FileNotFoundException e) {
                        log.info("The live update message was deleted, posting a new one");
                    }
                }
                messageId = discord.api("POST", "/channels/" + channelId + "/messages", body, files).getAsJsonObject().get("id").getAsString();
            }
        } catch (Exception e) {
            log.warning("Live update failed: " + e.getMessage());
        }
    }

    /** Reuses the bot's most recent status message in the channel so restarts don't spam new ones. */
    private String findOwnMessage() throws IOException {
        for (JsonElement m : discord.api("GET", "/channels/" + channelId + "/messages?limit=50", null, Map.of()).getAsJsonArray()) {
            JsonObject msg = m.getAsJsonObject();
            if (msg.getAsJsonObject("author").get("id").getAsString().equals(selfId) && msg.getAsJsonArray("embeds").size() > 0) {
                return msg.get("id").getAsString();
            }
        }
        return null;
    }

    // ---- Status panel ----

    private Ping.Status ping(Server s) {
        try {
            Ping.Status status = Ping.ping(s.host(), s.port());
            lastSeen.put(s, status);
            return status;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Mirrors the MCStatus bot's status panel, shaped by the panel: section of config.yml.
     * A null status renders the offline panel. The server's own icon is added to {@code files}.
     */
    private JsonObject embed(Server s, Ping.Status st, Map<String, byte[]> files) {
        JsonObject e = new JsonObject();
        e.addProperty("title", s.name().equals("Minecraft Server") ? label("default-title") : s.name());
        if (!s.website().isEmpty()) {
            e.addProperty("url", s.website().matches("(?i)https?://.*") ? s.website() : "https://" + s.website());
        }

        Ping.Status last = st != null ? st : lastSeen.get(s);
        boolean inline = show("inline-fields");
        JsonArray fields = new JsonArray();

        if (show("show-status")) {
            fields.add(field(label("status-label"), label(st != null ? "online-text" : "offline-text"), inline));
        }
        if (show("show-player-count")) {
            int max = last != null ? last.max() : s.maxPlayers();
            String count = String.valueOf(st != null ? st.online() : 0);
            fields.add(field(label("player-count-label"), show("show-max-players") && max >= 0 ? count + "/" + max : count, inline));
        }
        if (show("show-version")) {
            String version = !s.version().isEmpty() ? s.version() : last != null && !last.version().isEmpty() ? last.version() : "Unknown";
            fields.add(field(label("version-label"), version, inline));
        }

        if (st == null) {
            e.addProperty("color", offlineColor);
            if (show("show-motd")) {
                e.addProperty("description", label("offline-description"));
            }
        } else {
            e.addProperty("color", onlineColor);
            if (show("show-motd")) {
                e.addProperty("description", st.motd().isEmpty() ? label("no-motd-text") : st.motd());
            }
            String players = st.players().stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .map(p -> "`" + p.replace("`", "") + "`")
                    .collect(Collectors.joining(", "));
            if (show("show-player-list") && !players.isEmpty()) {
                fields.add(field(label("player-list-label"), players.length() > 1022 ? players.substring(0, 1022) : players, false));
            }
        }
        e.add("fields", fields);

        if (!s.footer().isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", s.footer());
            e.add("footer", footer);
        }
        if (show("show-timestamp")) {
            e.addProperty("timestamp", Instant.now().toString());
        }
        if (show("show-icon")) {
            // Configured URL, else the server's own server-icon.png, else the MCStatus default
            String url = s.icon();
            if (url.isEmpty() && last != null && last.icon() != null) {
                String file = "icon-" + servers.indexOf(s) + ".png";
                files.put(file, last.icon());
                url = "attachment://" + file;
            }
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", url.isEmpty() ? DEFAULT_ICON : url);
            e.add("thumbnail", thumbnail);
        }
        return e;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    /** Message body. Listing only this update's files makes edits drop the previous icons. */
    private static JsonObject body(JsonArray embeds, Map<String, byte[]> files) {
        JsonObject b = new JsonObject();
        b.add("embeds", embeds);
        JsonArray attachments = new JsonArray();
        int i = 0;
        for (String name : files.keySet()) {
            JsonObject a = new JsonObject();
            a.addProperty("id", i++);
            a.addProperty("filename", name);
            attachments.add(a);
        }
        b.add("attachments", attachments);
        return b;
    }
}
