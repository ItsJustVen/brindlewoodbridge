# BrindleWood Bridge (Paper plugin)

The game-server half of the Discord↔Minecraft bridge. Talks to the bot's
`bridge.js` feature over an AES-256-GCM-encrypted WebSocket connection —
see `src/lib/bridgeCrypto.js` on the bot side for the exact envelope format
this must match byte-for-byte.

## Status: skeleton, not yet built/tested

This sandbox has no network access to Maven Central or PaperMC's repo
(both are blocked by org policy here), so `mvn package` has **not** been
run against this — I wrote it carefully against the Paper 1.21 API and
Java-WebSocket 1.5.6's documented interfaces, but treat it as a first pass
to build and test on your own machine, not a verified artifact. The bot
side (`src/`) *has* been syntax-checked throughout; this hasn't.

## Building

Requires JDK 21 and Maven, with normal internet access (unlike this sandbox):

```
cd plugin
mvn package
```

Produces `target/BrindleWoodBridge.jar` — drop it in your Paper server's
`plugins/` folder.

## Setup

1. Start the bot first, then in Discord run `/bridge pair` — it gives you a
   6-digit code.
2. Edit `plugins/BrindleWoodBridge/config.yml`:
   - `bridge.secret` must **exactly** match `BRIDGE_SECRET` in the bot's `.env`.
   - `bridge.host`/`bridge.port` point at wherever the bot process runs.
3. Either put the pairing code in `config.yml`'s `pairCode` field and restart,
   or run `/bwbridge pair <code>` from the server console/as an op.
4. Once paired, the bot remembers it — no need to re-pair on normal restarts.

## What's implemented

- Encrypted hello/pairing handshake, auto-reconnect
- Chat, join, leave, death, advancement → forwarded to Discord
- Console log relay (via a Log4j2 appender), with `/bridge console` /
  the console channel able to run commands back on the server
- `/link` → generates a code, sent to the bot for the Discord-side `/link code:`
- Playtime heartbeat (feeds the leveling system's game-XP)
- `setRank` hook — runs a configurable command template (works with any
  permissions plugin, since it's just running a console command you define)

## What's a stub, deliberately

- **`requestRankSync` (game→Discord rank push):** logs a message instead of
  reading groups from a permissions plugin. BrindleWood's current perms setup
  wasn't specified, so this is a hook to fill in rather than a guess at
  LuckPerms/PermissionsEx/whatever — search `requestRankSync` in
  `BrindleWoodBridgePlugin.java`.
