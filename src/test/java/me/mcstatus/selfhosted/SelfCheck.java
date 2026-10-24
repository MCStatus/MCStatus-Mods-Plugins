package me.mcstatus.selfhosted;

import java.util.List;

/** Run with {@code ./gradlew check}. */
public class SelfCheck {
    public static void main(String[] args) {
        Ping.Status s = Ping.parse("""
                {"version":{"name":"Paper 1.21.4","protocol":769},
                 "players":{"max":50,"online":2,"sample":[{"name":"Notch","id":"a"},{"name":"§cjeb_","id":"b"},{"name":"§r","id":"c"}]},
                 "description":{"text":"","extra":[{"text":"Hello ","color":"gold"},{"text":"§aWorld"},["!"]]}}""");
        check(s.motd().equals("Hello World!"), "motd: " + s.motd());
        check(s.version().equals("Paper 1.21.4"), "version");
        check(s.online() == 2 && s.max() == 50, "counts");
        check(s.players().equals(List.of("Notch", "jeb_")), "players: " + s.players());

        Ping.Status plain = Ping.parse("{\"description\":\"§6Plain §lMOTD\",\"favicon\":\"data:image/png;base64,iVBO\\nRw==\"}");
        check(plain.motd().equals("Plain MOTD"), "string motd");
        check(plain.icon().length == 4 && plain.icon()[1] == 'P', "favicon (with a line break) decodes");
        check(s.icon() == null, "no favicon -> null");

        List<MCStatus.Server> servers = List.of(
                new MCStatus.Server("Survival", "h", 1, "", "", "", "", -1),
                new MCStatus.Server("Creative", "h", 2, "", "", "", "", -1));
        check(MCStatus.find(servers, "") == servers.get(0), "no name -> first server");
        check(MCStatus.find(servers, "creative") == servers.get(1), "case-insensitive name");
        check(MCStatus.find(servers, "nope") == null, "unknown name");
        System.out.println("SelfCheck OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
