package me.mcstatus.selfhosted;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Shared startup for the Fabric, Forge and NeoForge entrypoints. It touches no Minecraft classes,
 * so one jar runs on every game version: status comes from pinging the server, and the offline
 * panel is posted from a JVM shutdown hook.
 */
final class ModEntry {

    private ModEntry() {}

    static void start() {
        if (ModEntry.class.getClassLoader().getResource("org/lwjgl/Version.class") != null) {
            return; // LWJGL only ships with the client: dedicated servers only
        }

        int port = 25565;
        try (Reader reader = Files.newBufferedReader(Path.of("server.properties"))) {
            Properties props = new Properties();
            props.load(reader);
            port = Integer.parseInt(props.getProperty("server-port", "25565").trim());
        } catch (IOException | NumberFormatException ignored) {
            // first boot writes server.properties later; vanilla default it is
        }

        MCStatus mc = MCStatus.start(Path.of("config", "mcstatus"), port, slf4jLogger());
        if (mc != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(mc::stop, "MCStatus-Shutdown"));
        }
    }

    /** A JUL logger that writes through SLF4J, which Minecraft has shipped since 1.18. */
    private static Logger slf4jLogger() {
        org.slf4j.Logger out = org.slf4j.LoggerFactory.getLogger("MCStatus");
        Logger log = Logger.getLogger("MCStatus");
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    out.warn(r.getMessage(), r.getThrown());
                } else {
                    out.info(r.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return log;
    }
}
