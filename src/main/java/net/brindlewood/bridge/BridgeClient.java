package net.brindlewood.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.function.BiConsumer;

/**
 * WebSocket client to the Discord bot's bridge server. Handles the
 * encrypted envelope, the one-time pairing handshake, and reconnection —
 * everything protocol-specific (chat/join/death/etc.) is dispatched out to
 * BrindleWoodBridgePlugin via the onMessage callback, keeping this class
 * focused on "stay connected and speak the envelope format correctly."
 */
public class BridgeClient {
    private final BrindleWoodBridgePlugin plugin;
    private final BridgeCrypto crypto;
    private final String host;
    private final int port;
    private final int reconnectDelaySeconds;
    private final BiConsumer<String, JsonObject> onMessage;

    private WebSocketClient socket;
    private boolean authenticated = false;
    private boolean intentionalClose = false;

    public BridgeClient(BrindleWoodBridgePlugin plugin, String host, int port, String secret,
                         int reconnectDelaySeconds, BiConsumer<String, JsonObject> onMessage) {
        this.plugin = plugin;
        this.crypto = new BridgeCrypto(secret);
        this.host = host;
        this.port = port;
        this.reconnectDelaySeconds = reconnectDelaySeconds;
        this.onMessage = onMessage;
    }

    public boolean isConnected() {
        return socket != null && socket.isOpen() && authenticated;
    }

    public void connect(String pairCodeOrNull) {
        intentionalClose = false;
        try {
            URI uri = new URI("ws://" + host + ":" + port);
            socket = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    plugin.getLogger().info("[Bridge] Connected, authenticating...");
                    JsonObject hello = new JsonObject();
                    if (pairCodeOrNull != null && !pairCodeOrNull.isEmpty()) hello.addProperty("pairCode", pairCodeOrNull);
                    send("hello", hello);
                }

                @Override
                public void onMessage(String message) {
                    String decrypted = crypto.decrypt(message);
                    if (decrypted == null) {
                        plugin.getLogger().warning("[Bridge] Received an envelope that failed to decrypt (wrong secret?) — ignoring.");
                        return;
                    }
                    JsonObject envelope = JsonParser.parseString(decrypted).getAsJsonObject();
                    String type = envelope.get("type").getAsString();
                    JsonObject data = envelope.has("data") ? envelope.getAsJsonObject("data") : new JsonObject();

                    if (!authenticated) {
                        if (type.equals("helloAck")) {
                            authenticated = true;
                            plugin.getLogger().info("[Bridge] Authenticated with the bot.");
                        } else if (type.equals("pairFailed")) {
                            plugin.getLogger().severe("[Bridge] Pairing failed — check the code and try /bwbridge pair <code> again.");
                            close();
                        }
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> onMessage.accept(type, data));
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    authenticated = false;
                    plugin.getLogger().warning("[Bridge] Disconnected (" + reason + "). Reconnecting in " + reconnectDelaySeconds + "s...");
                    if (!intentionalClose) scheduleReconnect(null);
                }

                @Override
                public void onError(Exception ex) {
                    plugin.getLogger().warning("[Bridge] Connection error: " + ex.getMessage());
                }
            };
            socket.connect();
        } catch (Exception e) {
            plugin.getLogger().severe("[Bridge] Failed to start connection: " + e.getMessage());
            scheduleReconnect(pairCodeOrNull);
        }
    }

    private void scheduleReconnect(String pairCodeOrNull) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> connect(pairCodeOrNull), reconnectDelaySeconds * 20L);
    }

    public void close() {
        intentionalClose = true;
        if (socket != null) socket.close();
    }

    public void send(String type, JsonObject data) {
        if (socket == null || !socket.isOpen()) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.add("data", data);
        String encrypted = crypto.encrypt(envelope.toString());
        socket.send(encrypted);
    }
}
