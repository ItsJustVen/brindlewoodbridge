package net.brindlewood.bridge;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Sends the bot a small "perf" message on a timer so it can chart server speed and warn staff about lag.
 * Runs on the main thread (a repeating Bukkit task), so every reading is a consistent snapshot. Read-only:
 * nothing here changes the game. Mirrors the "perf" schema in the bot's src/core/bridgeProtocol.js.
 */
final class PerfReporter {
    private final BrindleWoodBridgePlugin plugin;

    PerfReporter(BrindleWoodBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Rounds to two decimals; keeps the JSON short and avoids long floating point tails. */
    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Paper reports TPS slightly above 20 at times; the bot treats 20 as perfect, so cap it here too. */
    static double capTps(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 20.0;
        return Math.max(0.0, Math.min(20.0, v));
    }

    void report() {
        BridgeClient client = plugin.getBridgeClient();
        if (client == null || !client.isConnected()) return;
        try {
            double[] tps = Bukkit.getTPS(); // Paper: 1m, 5m, 15m
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            int chunks = 0;
            int entities = 0;
            for (World w : Bukkit.getWorlds()) {
                chunks += w.getChunkCount();
                entities += w.getEntityCount();
            }
            JsonObject d = new JsonObject();
            d.addProperty("tps1", round2(capTps(tps.length > 0 ? tps[0] : 20.0)));
            d.addProperty("tps5", round2(capTps(tps.length > 1 ? tps[1] : 20.0)));
            d.addProperty("tps15", round2(capTps(tps.length > 2 ? tps[2] : 20.0)));
            d.addProperty("mspt", round2(Math.max(0.0, Bukkit.getAverageTickTime())));
            d.addProperty("memUsedMb", (int) Math.max(0, Math.min(1_000_000, usedMb)));
            d.addProperty("memMaxMb", (int) Math.max(0, Math.min(1_000_000, maxMb)));
            d.addProperty("players", Bukkit.getOnlinePlayers().size());
            d.addProperty("chunks", Math.min(chunks, 10_000_000));
            d.addProperty("entities", Math.min(entities, 10_000_000));
            client.send("perf", d);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Bridge] Could not collect performance numbers: " + t.getMessage());
        }
    }
}
