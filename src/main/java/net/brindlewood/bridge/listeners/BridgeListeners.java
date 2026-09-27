package net.brindlewood.bridge.listeners;

import com.google.gson.JsonObject;
import net.brindlewood.bridge.BrindleWoodBridgePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Translates Bukkit/Paper events into bridge messages. Each handler builds
 * a small JsonObject and hands it to the plugin's BridgeClient — this class
 * has no knowledge of the wire format or encryption, only "what happened."
 */
public class BridgeListeners implements Listener {
    private final BrindleWoodBridgePlugin plugin;

    public BridgeListeners(BrindleWoodBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        data.addProperty("message", message);
        plugin.getBridgeClient().send("chat", data);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        plugin.getBridgeClient().send("join", data);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        plugin.getBridgeClient().send("leave", data);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        data.addProperty("message", PlainTextComponentSerializer.plainText().serialize(event.deathMessage() != null ? event.deathMessage() : net.kyori.adventure.text.Component.text(player.getName() + " died")));
        plugin.getBridgeClient().send("death", data);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        String key = event.getAdvancement().getKey().getKey();
        // Skip Minecraft's internal "recipe unlock" pseudo-advancements —
        // they fire constantly and aren't meaningful player achievements.
        if (key.startsWith("recipes/")) return;
        String title = key;
        if (event.getAdvancement().getDisplay() != null) {
            title = PlainTextComponentSerializer.plainText().serialize(event.getAdvancement().getDisplay().title());
        }
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        data.addProperty("title", title);
        plugin.getBridgeClient().send("advancement", data);
    }
}
