package net.brindlewood.bridge;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Tells Java and Bedrock players apart without a compile-time Floodgate dependency.
 * Uses the Floodgate API by reflection when present. If Floodgate is missing or its API
 * changes, falls back to the documented Floodgate UUID layout (most significant bits are 0 and
 * the XUID is the least significant bits). Nothing here assumes a username prefix.
 */
public final class FloodgateInfo {
    public record Info(String edition, String xuid) {}

    private FloodgateInfo() {}

    public static Info of(Player player) {
        UUID id = player.getUniqueId();
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Method isFg = api.getMethod("isFloodgatePlayer", UUID.class);
            if (Boolean.TRUE.equals(isFg.invoke(inst, id))) {
                Object fp = api.getMethod("getPlayer", UUID.class).invoke(inst, id);
                String xuid = fp == null ? null : String.valueOf(fp.getClass().getMethod("getXuid").invoke(fp));
                if (xuid == null || !xuid.matches("\\d{1,20}")) xuid = xuidFromUuid(id);
                return new Info("bedrock", xuid);
            }
            return new Info("java", null);
        } catch (ClassNotFoundException e) {
            // Floodgate not installed: fall through to the UUID layout check.
        } catch (ReflectiveOperationException | RuntimeException e) {
            // API changed or unavailable: use the fallback below.
        }
        if (id.getMostSignificantBits() == 0L) return new Info("bedrock", xuidFromUuid(id));
        return new Info("java", null);
    }

    private static String xuidFromUuid(UUID id) {
        long lsb = id.getLeastSignificantBits();
        return lsb >= 0 ? Long.toString(lsb) : Long.toUnsignedString(lsb);
    }
}
