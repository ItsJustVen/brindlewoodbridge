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

    public BridgeClient getBridgeClient() {
        return bridgeClient;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();

        String host = cfg.getString("bridge.host", "127.0.0.1");
        int port = cfg.getInt("bridge.port", 8443);
        String secret = cfg.getString("bridge.secret", "");
        int reconnectDelay = cfg.getInt("bridge.reconnectDelaySeconds", 10);

        if (secret.isEmpty() || secret.equals("CHANGE_ME_TO_MATCH_BRIDGE_SECRET")) {
            getLogger().severe("bridge.secret is not set in config.yml — it must match BRIDGE_SECRET in the bot's .env. The bridge will not connect until this is fixed.");
        }

        bridgeClient = new BridgeClient(this, host, port, secret, reconnectDelay, this::handleBridgeMessage);
        String pairCode = cfg.getString("pairCode", "");
        bridgeClient.connect(pairCode.isEmpty() ? null : pairCode);

        getServer().getPluginManager().registerEvents(new BridgeListeners(this), this);
        getCommand("link").setExecutor(new LinkCommand(this));
        getCommand("bwbridge").setExecutor(new BridgeAdminCommand(this));

        // Console relay — attach to the server's root Log4j2 logger.
        consoleAppender = ConsoleRelayAppender.create(line -> {
            JsonObject data = new JsonObject();
            data.addProperty("line", line);
            if (bridgeClient.isConnected()) bridgeClient.send("console", data);
        });
        ((Logger) LogManager.getRootLogger()).addAppender(consoleAppender);

        // Playtime heartbeat — feeds the leveling system's game-XP-per-minute.
        int heartbeatMinutes = cfg.getInt("playtimeHeartbeatMinutes", 5);
        long ticks = heartbeatMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::sendPlaytimeHeartbeats, ticks, ticks);

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
        switch (type) {
            case "command" -> {
                String cmd = data.get("command").getAsString();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            case "setRank" -> {
                String uuid = data.get("uuid").getAsString();
                String role = data.get("role").getAsString();
                Player player = Bukkit.getPlayer(java.util.UUID.fromString(uuid));
                String playerName = player != null ? player.getName() : uuid;
                String template = getConfig().getString("rankSyncCommandTemplate", "");
                if (!template.isEmpty()) {
                    String cmd = template.replace("{player}", playerName).replace("{role}", role);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }
            case "requestRankSync" -> {
                // Plugin-side rank source is whatever permissions plugin this
                // server runs — wire this up to actually read groups and send
                // "rankUpdate" messages back once you've picked one. Left as
                // a hook rather than hardcoding a specific perms plugin.
                getLogger().info("[Bridge] Rank sync requested — implement group lookup for your permissions plugin here.");
            }
            case "linkConfirmed" -> {
                String uuid = data.get("uuid").getAsString();
                Player player = Bukkit.getPlayer(java.util.UUID.fromString(uuid));
                if (player != null) {
                    String rewardTemplate = getConfig().getString("linkRewardCommandTemplate", "");
                    if (!rewardTemplate.isEmpty()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rewardTemplate.replace("{player}", player.getName()));
                    }
                    player.sendMessage("§aYour Discord account is now linked!");
                }
            }
            case "unlinked" -> {
                // No in-game action required by default; hook here if you
                // want to strip a linked-only perk on unlink.
            }
            default -> getLogger().warning("[Bridge] Unknown message type from bot: " + type);
        }
    }
}
