package net.brindlewood.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Bridge protocol v2. Mirrors src/core/bridgeProtocol.js in the bot - keep them in step.
 * Every frame is an AES-256-GCM envelope whose plaintext is
 * {"v":2,"id":..,"ts":..,"dir":"p2b"|"b2p","type":..,"data":{..}}.
 */
public final class BridgeProtocol {
    public static final int VERSION = 2;
    public static final long MAX_SKEW_MS = 120_000L;
    public static final String DIR_PLUGIN_TO_BOT = "p2b";
    public static final String DIR_BOT_TO_PLUGIN = "b2p";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BridgeProtocol() {}

    public static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Builds and encrypts one outgoing frame. */
    public static String seal(BridgeCrypto crypto, String type, JsonObject data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("v", VERSION);
        msg.addProperty("id", UUID.randomUUID().toString());
        msg.addProperty("ts", System.currentTimeMillis());
        msg.addProperty("dir", DIR_PLUGIN_TO_BOT);
        msg.addProperty("type", type);
        msg.add("data", data == null ? new JsonObject() : data);
        return crypto.encrypt(msg.toString());
    }

    /** A checked incoming message. */
    public record Message(String id, String type, JsonObject data) {}

    /** Remembers recent message ids so a captured frame cannot be delivered twice. */
    public static final class ReplayCache {
        private final Map<String, Long> seen = new HashMap<>();
        private static final int MAX = 10_000;
        private static final long TTL = MAX_SKEW_MS * 2 + 60_000L;

        public synchronized boolean accept(String id, long now) {
            if (seen.size() >= MAX) sweep(now);
            Long at = seen.get(id);
            if (at != null && now - at <= TTL) return false;
            if (seen.size() >= MAX) return false; // saturated: fail closed
            seen.put(id, now);
            return true;
        }

        private void sweep(long now) {
            Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue() > TTL) it.remove();
        }
    }

    /** Decrypts and validates a frame from the bot. Returns null (and sets reason[0]) when it must be dropped. */
    public static Message open(BridgeCrypto crypto, String raw, ReplayCache replay, String[] reason) {
        String plain = crypto.decrypt(raw);
        if (plain == null) { reason[0] = "crypto"; return null; }
        try {
            JsonElement el = JsonParser.parseString(plain);
            if (!el.isJsonObject()) { reason[0] = "format"; return null; }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("v") || o.get("v").getAsInt() != VERSION) { reason[0] = "version"; return null; }
            String id = o.get("id").getAsString();
            String type = o.get("type").getAsString();
            if (id.length() < 8 || id.length() > 64 || !type.matches("[A-Za-z]{1,32}")) { reason[0] = "format"; return null; }
            if (!DIR_BOT_TO_PLUGIN.equals(o.get("dir").getAsString())) { reason[0] = "direction"; return null; }
            long ts = o.get("ts").getAsLong();
            long now = System.currentTimeMillis();
            if (Math.abs(now - ts) > MAX_SKEW_MS) { reason[0] = "stale"; return null; }
            if (!replay.accept(id, now)) { reason[0] = "replay"; return null; }
            JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : new JsonObject();
            return new Message(id, type, data);
        } catch (RuntimeException e) {
            reason[0] = "format";
            return null;
        }
    }
}
