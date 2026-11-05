# BrindleWood Bridge (Paper plugin)

The game-server half of the BrindleWood Discord bot's bridge (protocol v2). It connects out to the bot
over an AES-256-GCM encrypted WebSocket. Keep `src/main/java/.../BridgeProtocol.java` in step with the
bot's `src/core/bridgeProtocol.js`.

## Build
GitHub Actions compiles every branch (`Verify build`); `main` also publishes the jar to the
`build-output` branch. Locally: JDK 21 + `mvn package` -> `target/BrindleWoodBridge.jar`.

## Setup
Edit `plugins/BrindleWoodBridge/config.yml`: `bridge.secret` must equal `BRIDGE_SECRET` in the bot's
`.env`; `bridge.host`/`bridge.port` must point at the bot (`BRIDGE_PORT`). Restart the plugin/server.
`/bwbridge status` shows the connection.

## What it does
- Chat, join (with first-join flag and Java/Bedrock edition), leave, death, advancement -> bot
  (chat that another plugin cancelled, e.g. a mute, is not relayed)
- Console log relay (player IPs masked; password-type commands dropped)
- `/link` code generation; one-time link rewards (a reward only counts once its command actually ran)
- Playtime heartbeat; LuckPerms rank reports (Minecraft -> Discord only); installed-plugin inventory
- Console commands from the bot, only if on BOTH the bot's and this plugin's allowlists
