package net.brindlewood.bridge;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which link rewards were already handed out, by the reward id the bot sends.
 * A retry or reconnect that repeats the same id is acknowledged without giving the reward again.
 * The id is recorded BEFORE the command runs, so a crash can at worst skip a reward (which staff
 * can give by hand), never duplicate one.
 */
final class RewardLedger {
    private static final int MAX = 5000;
    private final File file;
    private final Set<String> granted = new LinkedHashSet<>();

    RewardLedger(File dataFolder) {
        this.file = new File(dataFolder, "rewards.yml");
        if (file.exists()) granted.addAll(YamlConfiguration.loadConfiguration(file).getStringList("granted"));
    }

    synchronized boolean has(String id) { return granted.contains(id); }

    synchronized boolean claim(String id) {
        if (!granted.add(id)) return false;
        while (granted.size() > MAX) granted.remove(granted.iterator().next());
        YamlConfiguration y = new YamlConfiguration();
        y.set("granted", new ArrayList<>(granted));
        try {
            y.save(file);
        } catch (IOException e) {
            granted.remove(id); // could not persist: refuse rather than risk a duplicate later
            return false;
        }
        return true;
    }

    synchronized List<String> snapshot() { return new ArrayList<>(granted); }
}
