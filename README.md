# MCStatus Self-Hosted

![VibeCoded with Claude Opus 5.5](https://img.shields.io/badge/VibeCoded%20with-Claude%20Opus%205.5-D97757?logo=claude&logoColor=white)

Run the MCStatus Discord bot from inside your own Minecraft server, using your own bot.

- `/status` shows the server status panel (`/status <server>` when you have several servers).
- Optional text command such as `!status` / `!status <server>`.
- A live update message that the bot keeps edited in a channel, and marks offline when the server stops.
- Up to 5 servers. On a proxy, list your backend servers to get one panel each.

## Supported platforms

One jar, `MCStatus-Selfhosted-<version>.jar`, works on all of these:

| Platform | Versions | Install to |
|---|---|---|
| Paper / Spigot | 1.18 – 26.x | `plugins/` |
| Fabric | 1.18 – 26.x (Fabric API not needed) | `mods/` |
| Forge | 1.18 – 26.x | `mods/` |
| NeoForge | 1.20.1 – 26.x | `mods/` |
| Velocity | 3.x, 4.x | `plugins/` |
| BungeeCord / Waterfall | current | `plugins/` |

The mod is server-side only, so players don't need to install it.

## Tested versions

Every row ran the same release jar against a real Discord server (2026-10-23). Each run checks that the live update
message shows the server online with its `server-icon.png` attached, that `!status` gets a reply, and that the message
switches to offline when the server stops. Proxy rows list the proxy itself and a Paper 26.3 backend.

| Platform | Minecraft | Loader / build | Java | Result |
|---|---|---|---|---|
| Paper | 1.18.2 | Paper build 388 | 17 | ✅ Pass |
| Paper | 1.20.4 | Paper build 499 | 17 | ✅ Pass |
| Paper | 1.21.4 | Paper build 232 | 21 | ✅ Pass |
| Paper | 26.1.2 | Paper build 74 | 25 | ✅ Pass |
| Paper | 26.3 | Paper build 218 | 25 | ✅ Pass |
| Fabric | 1.18.2 | Fabric Loader 0.20.1 | 17 | ✅ Pass |
| Fabric | 1.20.1 | Fabric Loader 0.20.1 | 17 | ✅ Pass |
| Fabric | 1.21.1 | Fabric Loader 0.20.1 | 21 | ✅ Pass |
| Fabric | 26.1.2 | Fabric Loader 0.20.1 | 25 | ✅ Pass |
| Fabric | 26.3 | Fabric Loader 0.20.1 | 25 | ✅ Pass |
| Forge | 1.18.2 | Forge 40.3.0 | 17 | ✅ Pass |
| Forge | 1.19.2 | Forge 43.5.0 | 17 | ✅ Pass |
| Forge | 1.20.1 | Forge 47.4.28 | 17 | ✅ Pass |
| Forge | 1.21.1 | Forge 52.1.20 | 21 | ✅ Pass |
| Forge | 26.3 | Forge 66.1.0 | 25 | ✅ Pass |
| NeoForge | 1.20.1 | NeoForge 47.1.106 | 17 | ✅ Pass |
| NeoForge | 1.20.4 | NeoForge 20.4.253 | 17 | ✅ Pass |
| NeoForge | 1.21.1 | NeoForge 21.1.264 | 21 | ✅ Pass |
| NeoForge | 26.3 | NeoForge 26.3.0.95 | 25 | ✅ Pass |
| Velocity | proxy (backend: Paper 26.3) | Velocity 3.5.1 build 615 | 25 | ✅ Pass |
| Velocity | proxy (backend: Paper 26.3) | Velocity 4.3.0 build 44 | 25 | ✅ Pass |
| BungeeCord | proxy (backend: Paper 26.3) | BungeeCord 26.1-R0.1 (da0a) | 25 | ✅ Pass |

## Setup

1. Create an application at https://discord.com/developers/applications and copy the token from the **Bot** page.
   If you want a text prefix like `!status`, also turn on **Message Content Intent** on that page.
2. Invite the bot to your server (replace `CLIENT_ID` with your application ID):
   `https://discord.com/oauth2/authorize?client_id=CLIENT_ID&scope=bot+applications.commands&permissions=84992`
3. Start the server once to generate the config:
   - Paper/Spigot/BungeeCord: `plugins/MCStatus/config.yml`
   - Velocity: `plugins/mcstatus/config.yml`
   - Fabric/Forge/NeoForge: `config/mcstatus/config.yml`
4. Fill in `bot-token`, `discord-server-id`, and optionally `prefix` and `live-update-channel-id`, then restart.

To copy IDs, turn on Developer Mode in Discord (Settings → Advanced), then right-click a server or channel and choose **Copy ID**.

## Customizing

Everything lives in `config.yml`, and every option is explained there:

- Per server: name, address, footer, website link, icon URL, version text, and the player cap to show while offline.
- `panel:` sets the colors and every label and text on the panel (so you can translate it). Each field can be shown or
  hidden: status, player count, the `/max` part of the player count, version, player list, MOTD, icon, and timestamp.
- `messages:` sets the command name (e.g. `/estado` and `!estado`), its descriptions, and the unknown-server reply.

The panel icon is the server's own `server-icon.png`, uploaded with the message. Servers without one show the MCStatus
icon, and `icon-url` overrides both.

## Notes

- Status comes from pinging the server, the same way the MCStatus bot does. The player list is whatever the server
  includes in its ping, and vanilla caps that at 12 names.
- Restart the server to apply config changes.

## Building

```
./gradlew build
```

Requires JDK 17 or newer. The jar is written to `build/libs/`.

Publishing a GitHub release runs `.github/workflows/release.yml`, which builds the jar with the release tag as its
version (`v1.2.0` becomes `1.2.0`) and attaches it to the release.
