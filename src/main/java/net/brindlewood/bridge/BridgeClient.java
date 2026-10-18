package net.brindlewood.bridge;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * WebSocket client to the Discord bot's bridge (protocol v2).
 *
 * Reliability rules:
 *  - exactly one live socket; the previous one is always closed before a new one opens
 *  - callbacks from an old socket are ignored (generation counter)
 *  - reconnect uses exponential backoff with full jitter, and the backoff only resets after
 *    a connection has stayed up for a while, so a flapping link cannot hammer the bot
 *  - the handshake must finish within HANDSHAKE_SECONDS or the socket is dropped and retried
 *  - send() is synchronized because the console appender calls it from log threads
 */
public class BridgeClient {
    private static final int HANDSHAKE_SECONDS = 10;
    private static final long STABLE_MS = 60_000L;

    private final BrindleWoodBridgePlugin plugin;
    private final BridgeCrypto crypto;
    private final String uri;
    private final int baseDelaySeconds;
    private final int maxDelaySeconds;
    private final BiConsumer<String, JsonObject> onMessage;

    private final Object lock = new Object();
    private WebSocketClient socket;
    private int generation = 0;
    private volatile boolean authenticated = false;
    private volatile boolean stopped = false;
    private int failures = 0;
    private long authenticatedAt = 0;
    private volatile long lastConnectedAt = 0;
    private volatile long lastDisconnectAt = 0;
    private volatile String lastProblem = "";

    public BridgeClient(BrindleWoodBridgePlugin plugin, String host, int port, boolean tls, String secret,
                        int baseDelaySeconds, int maxDelaySeconds, BiConsumer<String, JsonObject> onMessage) {
        this.plugin = plugin;
        this.crypto = new BridgeCrypto(secret);
        this.uri = (tls ? "wss://" : "ws://") + host + ":" + port;
        this.baseDelaySeconds = Math.max(1, baseDelaySeconds);
        this.maxDelaySeconds = Math.max(this.baseDelaySeconds, maxDelaySeconds);
        this.onMessage = onMessage;
    }

    public boolean isConnected() {
        WebSocketClient s = socket;
        return s != null && s.isOpen() && authenticated;
    }

    public String describe() {
        long now = System.currentTimeMillis();
        if (isConnected()) return "Connected and authenticated for " + ((now - lastConnectedAt) / 1000) + "s (protocol v" + BridgeProtocol.VERSION + ").";
        String since = lastDisconnectAt == 0 ? "never connected since start" : "down for " + ((now - lastDisconnectAt) / 1000) + "s";
        return "Not connected (" + since + ", " + failures + " failed attempt(s) in a row). Last problem: " + (lastProblem.isEmpty() ? "none" : lastProblem);
    }

    /** Starts (or restarts) the connection. Safe to call repeatedly. */
    public void connect() {
        stopped = false;
        open();
    }

    private void open() {
        final int gen;
        synchronized (lock) {
            if (stopped) return;
            closeQuietly(socket);
            authenticated = false;
            gen = ++generation;
        }
        try {
            final BridgeProtocol.ReplayCache replay = new BridgeProtocol.ReplayCache();
            final String cn = BridgeProtocol.newNonce();
            WebSocketClient ws = new WebSocketClient(new URI(uri)) {
                @Override
                public void onOpen(ServerHandshake h) {
                    if (gen != generation) return;
                    // Wait for the bot's challenge before saying hello.
                    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                        if (gen == generation && !authenticated && isOpen()) {
                            problem("handshake timed out");
                            closeQuietly(this);
                        }
                    }, HANDSHAKE_SECONDS * 20L);
                }

                @Override
                public void onMessage(String message) {
                    if (gen != generation) return;
                    String[] reason = new String[1];
                    BridgeProtocol.Message m = BridgeProtocol.open(crypto, message, replay, reason);
                    if (m == null) {
                        problem("bot frame rejected (" + reason[0] + ")" + ("crypto".equals(reason[0]) ? " - is bridge.secret identical to BRIDGE_SECRET?" : ""));
                        closeQuietly(this);
                        return;
                    }
                    if (!authenticated) {
                        if (m.type().equals("challenge")) {
                            JsonObject hello = new JsonObject();
                            hello.addProperty("nonce", m.data().get("nonce").getAsString());
                            hello.addProperty("cn", cn);
                            hello.addProperty("pluginVersion", plugin.getDescription().getVersion());
                            hello.addProperty("server", Bukkit.getServer().getName());
                            sendOn(this, "hello", hello);
                        } else if (m.type().equals("helloAck") && cn.equals(m.data().has("cn") ? m.data().get("cn").getAsString() : null)) {
                            authenticated = true;
                            authenticatedAt = System.currentTimeMillis();
                            lastConnectedAt = authenticatedAt;
                            lastProblem = "";
                            plugin.getLogger().info("[Bridge] Authenticated with the bot.");
                        } else {
                            problem("unexpected message during handshake: " + m.type());
                            closeQuietly(this);
                        }
                        return;
                    }
                    final String type = m.type();
                    final JsonObject data = m.data();
                    Bukkit.getScheduler().runTask(plugin, () -> onMessage.accept(type, data));
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (gen != generation) return;
                    boolean wasUp = authenticated;
                    authenticated = false;
                    lastDisconnectAt = System.currentTimeMillis();
                    if (wasUp && lastDisconnectAt - authenticatedAt >= STABLE_MS) failures = 0;
                    if (code == 4002) problem("the bot speaks a different protocol version; update the plugin and bot together");
                    plugin.getLogger().warning("[Bridge] Disconnected (code " + code + (reason == null || reason.isEmpty() ? "" : ", " + reason) + ").");
                    scheduleReconnect(gen);
                }

                @Override
                public void onError(Exception ex) {
                    problem(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            };
            ws.setConnectionLostTimeout(45); // detects half-open connections that never send a close frame
            synchronized (lock) {
                if (gen != generation || stopped) return;
                socket = ws;
            }
            ws.connect();
        } catch (Exception e) {
            problem("could not start connection: " + e.getMessage());
            scheduleReconnect(gen);
        }
    }

    private void problem(String text) {
        lastProblem = text;
        plugin.getLogger().warning("[Bridge] " + text);
    }

    private void scheduleReconnect(int gen) {
        synchronized (lock) {
            if (stopped || gen != generation) return;
            failures++;
            // Exponential backoff with full jitter: random in [base, min(max, base * 2^(failures-1))].
            long ceiling = Math.min((long) maxDelaySeconds, (long) baseDelaySeconds << Math.min(failures - 1, 10));
            long delay = ThreadLocalRandom.current().nextLong(baseDelaySeconds, ceiling + 1);
            plugin.getLogger().info("[Bridge] Reconnecting in " + delay + "s (attempt " + failures + ").");
            try {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> { if (gen == generation) open(); }, delay * 20L);
            } catch (IllegalStateException e) {
                // Plugin is being disabled; nothing to do.
            }
        }
    }

    public void close() {
        synchronized (lock) {
            stopped = true;
            generation++;
            authenticated = false;
            closeQuietly(socket);
            socket = null;
        }
    }

    /** Forces a fresh connection now (used by /bwbridge reconnect). */
    public void reconnectNow() {
        synchronized (lock) {
            stopped = false;
            failures = 0;
        }
        open();
    }

    private void closeQuietly(WebSocketClient ws) {
        if (ws == null) return;
        try { ws.close(); } catch (Exception ignored) { /* already closing */ }
    }

    public boolean send(String type, JsonObject data) {
        WebSocketClient s = socket;
        if (s == null || !s.isOpen() || !authenticated) return false;
        return sendOn(s, type, data);
    }

    private boolean sendOn(WebSocketClient s, String type, JsonObject data) {
        synchronized (lock) {
            if (!s.isOpen()) return false;
            try {
                s.send(BridgeProtocol.seal(crypto, type, data));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
