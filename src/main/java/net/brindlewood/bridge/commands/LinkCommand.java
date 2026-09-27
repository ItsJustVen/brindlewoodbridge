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

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        data.addProperty("code", code);
        plugin.getBridgeClient().send("linkCode", data);

        player.sendMessage(ChatColor.GREEN + "Your link code is: " + ChatColor.BOLD + code);
        player.sendMessage(ChatColor.GREEN + "In Discord, run: " + ChatColor.YELLOW + "/link code:" + code);
        return true;
    }
}
