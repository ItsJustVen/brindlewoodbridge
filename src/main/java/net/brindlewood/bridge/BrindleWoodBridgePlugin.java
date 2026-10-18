package net.brindlewood.bridge;

import com.google.gson.JsonObject;
import net.brindlewood.bridge.commands.BridgeAdminCommand;
import net.brindlewood.bridge.commands.LinkCommand;
import net.brindlewood.bridge.listeners.BridgeListeners;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class BrindleWoodBridgePlugin extends JavaPlugin {
    private BridgeClient bridgeClient;
    private ConsoleRelayAppender consoleAppender;
    private CommandGate commandGate = new CommandGate(java.util.List.of());
    private RewardLedger rewards;
    private final java.util.concurrent.atomic.AtomicInteger consoleLinesThisSecond = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long consoleWindow = 0;

    public BridgeClient getBridgeClient() {
        return bridgeClient;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();

        String host = cfg.getString("bridge.host", "127.0.0.1");
        int port = cfg.getInt("bridge.port", 8443);
        boolean tls = cfg.getBoolean("bridge.tls", false);
        String secret = cfg.getString("bridge.secret", "");
        int baseDelay = cfg.getInt("bridge.reconnectBaseSeconds", 5);
        int maxDelay = cfg.getInt("bridge.reconnectMaxSeconds", 300);

        if (secret.isEmpty() || secret.equals("CHANGE_ME_TO_MATCH_BRIDGE_SECRET")) {
            getLogger().severe("bridge.secret is not set in config.yml - it must match BRIDGE_SECRET in the bot's .env. The bridge will not connect until this is fixed.");
        } else {
            commandGate = new CommandGate(cfg.getStringList("console.allowed"));
            rewards = new RewardLedger(getDataFolder());
            bridgeClient = new BridgeClient(this, host, port, tls, secret, baseDelay, maxDelay, this::handleBridgeMessage);
            bridgeClient.connect();
        }
        if (bridgeClient == null) bridgeClient = new BridgeClient(this, host, port, tls, "unset-secret", baseDelay, maxDelay, (t, d) -> { });

        getServer().getPluginManager().registerEvents(new BridgeListeners(this), this);
        getCommand("link").setExecutor(new LinkCommand(this));
        getCommand("bwbridge").setExecutor(new BridgeAdminCommand(this));

        // Console relay — attach to the server's root Log4j2 logger.
        consoleAppender = ConsoleRelayAppender.create(line -> {
            if (!bridgeClient.isConnected()) return;
            long sec = System.currentTimeMillis() / 1000;
            if (sec != consoleWindow) { consoleWindow = sec; consoleLinesThisSecond.set(0); }
            if (consoleLinesThisSecond.incrementAndGet() > 40) return; // protect the bridge during log floods
            JsonObject data = new JsonObject();
            data.addProperty("line", line.length() > 900 ? line.substring(0, 900) : line);
            bridgeClient.send("console", data);
        });
        ((Logger) LogManager.getRootLogger()).addAppender(consoleAppender);

        // Playtime heartbeat — feeds the leveling system's game-XP-per-minute.
        int heartbeatMinutes = cfg.getInt("playtimeHeartbeatMinutes", 5);
        long ticks = heartbeatMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::sendPlaytimeHeartbeats, ticks, ticks);

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                LuckPermsRanks.subscribe(this);
            } catch (Throwable t) {
                getLogger().warning("[Bridge] Could not watch LuckPerms for rank changes: " + t.getMessage());
            }
        }

        getLogger().info("BrindleWoodBridge enabled.");
    }

    @Override
    public void onDisable() {
        if (consoleAppender != null) {
            ((Logger) LogManager.getRootLogger()).removeAppender(consoleAppender);
        }
        if (bridgeClient != null) bridgeClient.close();
    }

    private void sendPlaytimeHeartbeats() {
        int intervalMinutes = getConfig().getInt("playtimeHeartbeatMinutes", 5);
        for (Player player : Bukkit.getOnlinePlayers()) {
            JsonObject data = new JsonObject();
            data.addProperty("player", player.getName());
            data.addProperty("uuid", player.getUniqueId().toString());
            data.addProperty("minutes", intervalMinutes);
            bridgeClient.send("playtime", data);
        }
    }

    /** Dispatches a decrypted, authenticated message from the bot. Runs on the main thread (see BridgeClient). */
    private void handleBridgeMessage(String type, JsonObject data) {
        try {
            switch (type) {
                case "command" -> runAllowedCommand(data);
                case "requestRankSync" -> sendRankSync();
                case "linkConfirmed" -> grantLinkReward(data);
                case "unlinked" -> { /* no in-game action by default */ }
                case "discordChat" -> {
                    String discordUser = clean(data.get("discordUser").getAsString(), 32);
                    String message = clean(data.get("message").getAsString(), 200);
                    if (!message.isEmpty()) Bukkit.broadcastMessage("\u00a79[Discord] \u00a7f" + discordUser + "\u00a77: \u00a7f" + message);
                }
                default -> getLogger().warning("[Bridge] Ignoring unknown message type from bot: " + type);
            }
        } catch (RuntimeException e) {
            getLogger().warning("[Bridge] Could not handle a '" + type + "' message: " + e.getMessage());
        }
    }

    /** Removes formatting codes and control characters from text that came from Discord. */
    private static String clean(String text, int max) {
        String t = text.replaceAll("[\\p{Cntrl}\\u00a7]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private void runAllowedCommand(JsonObject data) {
        String requestId = data.has("requestId") ? data.get("requestId").getAsString() : null;
        String command = data.has("command") ? data.get("command").getAsString() : "";
        String refusal = commandGate.check(command);
        boolean ok = false;
        String detail;
        if (refusal != null) {
            detail = "refused by the plugin: " + refusal;
            getLogger().warning("[Bridge] Refused a console command from the bot (" + refusal + ").");
        } else {
            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            detail = ok ? "ran" : "the server did not recognise that command";
        }
        if (requestId != null) {
            JsonObject r = new JsonObject();
            r.addProperty("requestId", requestId);
            r.addProperty("ok", ok);
            r.addProperty("detail", detail);
            bridgeClient.send("commandResult", r);
        }
    }

    private void grantLinkReward(JsonObject data) {
        String rewardId = data.has("rewardId") ? data.get("rewardId").getAsString() : null;
        java.util.UUID uuid = java.util.UUID.fromString(data.get("uuid").getAsString());
        Player player = Bukkit.getPlayer(uuid);
        boolean ok = false;
        String detail;
        if (rewardId == null || rewardId.length() < 8) {
            detail = "missing reward id";
        } else if (rewards.has(rewardId)) {
            ok = true;
            detail = "already granted";
        } else if (player == null) {
            detail = "player is offline";
        } else {
            String template = getConfig().getString("linkRewardCommandTemplate", "");
            if (template.isEmpty()) {
                ok = true;
                detail = "no reward configured";
                rewards.claim(rewardId);
            } else if (!player.getName().matches("[A-Za-z0-9_.\\-]{1,32}")) {
                detail = "player name is not safe to use in a command";
            } else if (!rewards.claim(rewardId)) {
                detail = "could not record the reward";
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), template.replace("{player}", player.getName()).replace("{uuid}", uuid.toString()));
                ok = true;
                detail = "granted";
            }
            if (ok) player.sendMessage("\u00a7aYour Discord account is now linked!");
        }
        if (rewardId != null) {
            JsonObject r = new JsonObject();
            r.addProperty("rewardId", rewardId);
            r.addProperty("ok", ok);
            r.addProperty("detail", detail);
            bridgeClient.send("rewardResult", r);
        }
    }

    /** Pushes each online player's rank groups to the bot as "rankUpdate" messages (Minecraft to Discord only). */
    private void sendRankSync() {
        if (!bridgeClient.isConnected()) return;
        int sent = 0;
        for (Player player : Bukkit.getOnlinePlayers()) if (sendRankFor(player)) sent++;
        getLogger().info("[Bridge] Rank sync sent for " + sent + " online player(s).");
    }

    /** Reports one online player's groups. Called on request, on join, and when LuckPerms says they changed. */
    public boolean sendRankFor(Player player) {
        if (!bridgeClient.isConnected() || !player.isOnline()) return false;
        java.util.List<String> configured = getConfig().getStringList("rankSyncGroups");
        boolean luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
        java.util.List<String> groups;
        String primary;
        try {
            if (luckPerms) {
                groups = LuckPermsRanks.groupsOf(player, configured);
                primary = LuckPermsRanks.primaryOf(player);
            } else {
                groups = new java.util.ArrayList<>();
                for (String g : configured) {
                    if (player.hasPermission("group." + g)) groups.add(g);
                }
                primary = groups.isEmpty() ? "default" : groups.get(0);
            }
        } catch (Throwable t) {
            getLogger().warning("[Bridge] Rank lookup failed for " + player.getName() + ": " + t.getMessage());
            return false;
        }
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        data.addProperty("primary", primary);
        data.addProperty("edition", FloodgateInfo.of(player).edition());
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        groups.forEach(arr::add);
        data.add("groups", arr);
        return bridgeClient.send("rankUpdate", data);
    }

    /** Debounced push after a LuckPerms change (main thread). */
    private final java.util.Set<java.util.UUID> rankPushQueued = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void queueRankPush(java.util.UUID id, long delayTicks) {
        if (!rankPushQueued.add(id)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            rankPushQueued.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) sendRankFor(p);
        }, delayTicks);
    }
}
