package net.brindlewood.bridge;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope encryption matching the bot's src/lib/bridgeCrypto.js
 * exactly: key = SHA-256(secret), 12-byte random IV, 16-byte GCM tag, and
 * the same {"iv":...,"tag":...,"data":...} base64 JSON envelope shape.
 * Both sides must produce byte-identical envelopes for this to interop —
 * if you change one side's format, change the other.
 */
public class BridgeCrypto {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public BridgeCrypto(String secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            this.key = sha256.digest(secret.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive bridge key", e);
        }
    }

    /** Encrypts a JSON string payload into the envelope JSON string. */
    public String encrypt(String jsonPayload) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv); // 128-bit tag
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

            byte[] plaintext = jsonPayload.getBytes("UTF-8");
            byte[] encryptedWithTag = cipher.doFinal(plaintext);

            // Java appends the GCM tag to the ciphertext; Node keeps them
            // separate. Split the last 16 bytes off as the tag to match.
            int tagLen = 16;
            int dataLen = encryptedWithTag.length - tagLen;
            byte[] data = new byte[dataLen];
            byte[] tag = new byte[tagLen];
            System.arraycopy(encryptedWithTag, 0, data, 0, dataLen);
            System.arraycopy(encryptedWithTag, dataLen, tag, 0, tagLen);

            String b64Iv = Base64.getEncoder().encodeToString(iv);
            String b64Tag = Base64.getEncoder().encodeToString(tag);
            String b64Data = Base64.getEncoder().encodeToString(data);

            return "{\"iv\":\"" + b64Iv + "\",\"tag\":\"" + b64Tag + "\",\"data\":\"" + b64Data + "\"}";
        } catch (Exception e) {
            throw new RuntimeException("Bridge encryption failed", e);
        }
    }

    /** Decrypts an envelope JSON string back to the original JSON payload string, or null on any failure. */
    public String decrypt(String envelopeJson) {
        try {
            String iv64 = extractField(envelopeJson, "iv");
            String tag64 = extractField(envelopeJson, "tag");
            String data64 = extractField(envelopeJson, "data");
            if (iv64 == null || tag64 == null || data64 == null) return null;

            byte[] iv = Base64.getDecoder().decode(iv64);
            byte[] tag = Base64.getDecoder().decode(tag64);
            byte[] data = Base64.getDecoder().decode(data64);

            byte[] combined = new byte[data.length + tag.length];
            System.arraycopy(data, 0, combined, 0, data.length);
            System.arraycopy(tag, 0, combined, data.length, tag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

            byte[] plaintext = cipher.doFinal(combined);
            return new String(plaintext, "UTF-8");
        } catch (Exception e) {
            return null; // wrong secret, tampered data, or malformed envelope
        }
    }

    // Minimal hand-rolled extraction to avoid pulling in a JSON library just
    // for this fixed three-field shape. BridgeProtocol below uses a real
    // (tiny, hand-rolled) parser for the actual message payloads.
    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
