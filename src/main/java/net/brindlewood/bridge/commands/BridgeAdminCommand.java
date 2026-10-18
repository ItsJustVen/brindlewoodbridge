package net.brindlewood.bridge.commands;

import net.brindlewood.bridge.BrindleWoodBridgePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** /bwbridge status|reconnect - console/op-side bridge management. */
public class BridgeAdminCommand implements CommandExecutor {
    private final BrindleWoodBridgePlugin plugin;

    public BridgeAdminCommand(BrindleWoodBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /bwbridge <status|reconnect>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status" -> sender.sendMessage((plugin.getBridgeClient().isConnected() ? ChatColor.GREEN : ChatColor.RED) + plugin.getBridgeClient().describe());
            case "reconnect" -> {
                plugin.getBridgeClient().reconnectNow();
                sender.sendMessage(ChatColor.YELLOW + "Reconnecting...");
            }
            default -> sender.sendMessage("Usage: /bwbridge <status|reconnect>");
        }
        return true;
    }
}
