package me.mcstatus.selfhosted;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Minecraft Java server list ping, the same data the MCStatus bot reads. */
final class Ping {

    /** {@code icon} is the server-icon.png bytes, or null when the server has none. */
    record Status(String motd, String version, int online, int max, List<String> players, byte[] icon) {}

    private static final int TIMEOUT_MS = 3000;

    private Ping() {}

    static Status ping(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, -1); // protocol: "just pinging"
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostBytes.length);
            handshake.write(hostBytes);
            handshake.write(port >> 8);
            handshake.write(port);
            writeVarInt(handshake, 1); // next state: status

            OutputStream out = socket.getOutputStream();
            writePacket(out, handshake.toByteArray());
            writePacket(out, new byte[]{0x00}); // status request
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            readVarInt(in); // packet length
            if (readVarInt(in) != 0x00) {
                throw new IOException("Unexpected status packet");
            }
            byte[] json = new byte[readVarInt(in)];
            in.readFully(json);
            return parse(new String(json, StandardCharsets.UTF_8));
        }
    }

    static Status parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String version = "Unknown";
        if (root.has("version") && root.getAsJsonObject("version").has("name")) {
            version = stripCodes(root.getAsJsonObject("version").get("name").getAsString());
        }

        int online = 0;
        int max = 0;
        List<String> players = new ArrayList<>();
        if (root.has("players")) {
            JsonObject p = root.getAsJsonObject("players");
            online = p.has("online") ? p.get("online").getAsInt() : 0;
            max = p.has("max") ? p.get("max").getAsInt() : 0;
            if (p.has("sample")) {
                for (JsonElement e : p.getAsJsonArray("sample")) {
                    String name = stripCodes(e.getAsJsonObject().get("name").getAsString()).trim();
                    if (!name.isEmpty()) {
                        players.add(name);
                    }
                }
            }
        }

        String motd = root.has("description") ? text(root.get("description")).trim() : "";

        byte[] icon = null;
        String favicon = root.has("favicon") ? root.get("favicon").getAsString() : "";
        if (favicon.startsWith("data:image/png;base64,")) {
            try {
                icon = Base64.getMimeDecoder().decode(favicon.substring(favicon.indexOf(',') + 1));
            } catch (IllegalArgumentException ignored) {
                // malformed icon: fall back to the default
            }
        }
        return new Status(motd, version, online, max, players, icon);
    }

    /** Flattens a chat component (string, object with text/extra, or array) to plain text. */
    static String text(JsonElement component) {
        StringBuilder sb = new StringBuilder();
        appendText(component, sb);
        return stripCodes(sb.toString());
    }

    private static void appendText(JsonElement c, StringBuilder sb) {
        if (c == null || c.isJsonNull()) {
            return;
        }
        if (c.isJsonPrimitive()) {
            sb.append(c.getAsString());
        } else if (c.isJsonArray()) {
            for (JsonElement e : c.getAsJsonArray()) {
                appendText(e, sb);
            }
        } else {
            JsonObject o = c.getAsJsonObject();
            if (o.has("text")) {
                sb.append(o.get("text").getAsString());
            }
            if (o.has("extra")) {
                JsonArray extra = o.getAsJsonArray("extra");
                for (JsonElement e : extra) {
                    appendText(e, sb);
                }
            }
        }
    }

    static String stripCodes(String s) {
        return s.replaceAll("§.", "");
    }

    private static void writePacket(OutputStream out, byte[] data) throws IOException {
        ByteArrayOutputStream len = new ByteArrayOutputStream();
        writeVarInt(len, data.length);
        out.write(len.toByteArray());
        out.write(data);
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("Connection closed");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too big");
    }
}
