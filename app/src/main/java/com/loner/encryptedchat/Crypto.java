package com.loner.encryptedchat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import okio.ByteString;
import org.json.JSONObject;

/** Byte-for-byte compatible with web.html WebCrypto; works on Android 7.0. */
public final class Crypto {
    public static final int ITERATIONS = 600000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private Crypto() {}
    public static byte[] random(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }
    public static String b64(byte[] b) { return ByteString.of(b).base64(); }
    public static byte[] unb64(String s) {
        ByteString b = ByteString.decodeBase64(s);
        if (b == null) throw new IllegalArgumentException("Invalid Base64");
        return b.toByteArray();
    }
    public static String hash(String s) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    // Explicit UTF-8 avoids provider-dependent char conversion, and SHA256 PBKDF2
    // SecretKeyFactory is unavailable on some API 24/25 devices.
    public static byte[] pbkdf(String password, byte[] salt, int rounds) throws Exception {
        if (password.isEmpty() || rounds < 1) throw new IllegalArgumentException("Invalid KDF input");
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(passwordBytes, "HmacSHA256"));
        Arrays.fill(passwordBytes, (byte) 0);
        byte[] block = Arrays.copyOf(salt, salt.length + 4);
        block[block.length - 1] = 1;
        byte[] u = mac.doFinal(block), result = u.clone();
        for (int i = 1; i < rounds; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < 32; j++) result[j] ^= u[j];
        }
        Arrays.fill(u, (byte) 0);
        return result;
    }
    public static byte[] roomKey(String phrase, String name) throws Exception {
        return pbkdf(phrase, ("encrypted-chat-v1:" + hash(name)).getBytes(StandardCharsets.UTF_8), ITERATIONS);
    }
    public static byte[] crypt(int mode, byte[] key, byte[] iv, byte[] input, byte[] aad) throws Exception {
        if (key.length != 32 || iv.length != 12) throw new IllegalArgumentException("Invalid AES-GCM parameters");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }
    public static JSONObject encrypt(byte[] key, byte[] data) throws Exception {
        byte[] iv = random(12);
        return new JSONObject().put("iv", b64(iv)).put("ciphertext", b64(crypt(Cipher.ENCRYPT_MODE, key, iv, data, null)));
    }
    public static byte[] decrypt(byte[] key, JSONObject envelope) throws Exception {
        return crypt(Cipher.DECRYPT_MODE, key, unb64(envelope.getString("iv")), unb64(envelope.getString("ciphertext")), null);
    }
    public static JSONObject exportAccount(JSONObject account, String password) throws Exception {
        byte[] salt = random(32), key = pbkdf(password, salt, ITERATIONS);
        try {
            JSONObject out = encrypt(key, account.toString().getBytes(StandardCharsets.UTF_8));
            return out.put("format", "encrypted-chat-account").put("version", 1)
                .put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS)
                .put("cipher", "AES-256-GCM").put("salt", b64(salt));
        } finally { Arrays.fill(key, (byte) 0); }
    }
    public static JSONObject importAccount(JSONObject envelope, String password) throws Exception {
        if (!"encrypted-chat-account".equals(envelope.getString("format")) || envelope.getInt("version") != 1
            || !"PBKDF2-HMAC-SHA256".equals(envelope.getString("kdf"))
            || !"AES-256-GCM".equals(envelope.getString("cipher"))
            || envelope.getInt("iterations") != ITERATIONS) throw new IllegalArgumentException("Unsupported export format");
        byte[] salt = unb64(envelope.getString("salt"));
        if (salt.length != 32) throw new IllegalArgumentException("Invalid salt");
        byte[] key = pbkdf(password, salt, ITERATIONS);
        try { return new JSONObject(new String(decrypt(key, envelope), StandardCharsets.UTF_8)); }
        finally { Arrays.fill(key, (byte) 0); }
    }
}
