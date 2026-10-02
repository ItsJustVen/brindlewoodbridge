package net.brindlewood.bridge;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Isolated so the LuckPerms classes only load when LuckPerms is installed. */
final class LuckPermsRanks {
    private LuckPermsRanks() {}

    private static User user(Player player) {
        LuckPerms lp = LuckPermsProvider.get();
        User user = lp.getUserManager().getUser(player.getUniqueId());
        if (user == null) throw new IllegalStateException("LuckPerms user not loaded");
        return user;
    }

    static String primaryOf(Player player) {
        return user(player).getPrimaryGroup();
    }

    /** Groups the player holds (including inherited), limited to the configured list when it is non-empty. */
    static List<String> groupsOf(Player player, List<String> configured) {
        List<String> out = new ArrayList<>();
        user(player).getInheritedGroups(user(player).getQueryOptions()).forEach(g -> {
            String name = g.getName();
            if (configured.isEmpty() || configured.contains(name)) out.add(name);
        });
        return out;
    }
}
