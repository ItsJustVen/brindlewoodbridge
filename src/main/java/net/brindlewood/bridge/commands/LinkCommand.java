package net.brindlewood.bridge.commands;

import com.google.gson.JsonObject;
import net.brindlewood.bridge.BrindleWoodBridgePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.security.SecureRandom;

/** /link — generates a one-time code the player then submits in Discord via /link code:<code>. */
public class LinkCommand implements CommandExecutor {
    private final BrindleWoodBridgePlugin plugin;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CODES = 3;
    private static final long WINDOW_MS = 10 * 60_000L;
    private static final java.util.Map<java.util.UUID, java.util.Deque<Long>> ATTEMPTS = new java.util.concurrent.ConcurrentHashMap<>();

    public LinkCommand(BrindleWoodBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can link an account.");
            return true;
        }
        if (!plugin.getBridgeClient().isConnected()) {
            player.sendMessage(ChatColor.RED + "The Discord bridge isn't connected right now — try again in a moment.");
            return true;
        }

        long now = System.currentTimeMillis();
        java.util.Deque<Long> recent = ATTEMPTS.computeIfAbsent(player.getUniqueId(), k -> new java.util.ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) recent.pollFirst();
            if (recent.size() >= MAX_CODES) {
                player.sendMessage(ChatColor.RED + "You've asked for several codes recently. Use the latest one, or wait a few minutes.");
                return true;
            }
            recent.addLast(now);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        data.addProperty("code", code);
        net.brindlewood.bridge.FloodgateInfo.Info info = net.brindlewood.bridge.FloodgateInfo.of(player);
        data.addProperty("edition", info.edition());
        if (info.xuid() != null) data.addProperty("xuid", info.xuid());
        if (!plugin.getBridgeClient().send("linkCode", data)) {
            player.sendMessage(ChatColor.RED + "Couldn't reach the bot just now - try again in a moment.");
            return true;
        }

        player.sendMessage(ChatColor.GREEN + "Your link code (valid 10 minutes, one use) is: " + ChatColor.BOLD + code);
        player.sendMessage(ChatColor.GREEN + "In Discord, run: " + ChatColor.YELLOW + "/link code:" + code);
        return true;
    }
}
