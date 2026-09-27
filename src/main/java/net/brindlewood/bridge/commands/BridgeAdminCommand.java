package net.brindlewood.bridge.commands;

import net.brindlewood.bridge.BrindleWoodBridgePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** /bwbridge pair|status|reconnect — console/op-side bridge management. */
public class BridgeAdminCommand implements CommandExecutor {
    private final BrindleWoodBridgePlugin plugin;

    public BridgeAdminCommand(BrindleWoodBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /bwbridge <pair|status|reconnect> [code]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "pair" -> {
                if (args.length < 2) { sender.sendMessage("Usage: /bwbridge pair <code from /bridge pair in Discord>"); return true; }
                plugin.getBridgeClient().connect(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Attempting to pair with code " + args[1] + "...");
            }
            case "status" -> sender.sendMessage(plugin.getBridgeClient().isConnected()
                    ? ChatColor.GREEN + "Connected and authenticated."
                    : ChatColor.RED + "Not connected.");
            case "reconnect" -> {
                plugin.getBridgeClient().close();
                plugin.getBridgeClient().connect(null);
                sender.sendMessage(ChatColor.YELLOW + "Reconnecting...");
            }
            default -> sender.sendMessage("Usage: /bwbridge <pair|status|reconnect> [code]");
        }
        return true;
    }
}
