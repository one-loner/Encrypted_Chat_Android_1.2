package com.loner.encryptedchat;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Password-wrapped random data key, plus an outer Android Keystore envelope. */
public final class Vault {
    private static final String ALIAS = "encrypted-chat-vault-v1";
    private final AtomicFile file;
    private JSONObject data, header;
    private byte[] master;
    public Vault(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "vault.enc")); }
    public boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile().getPath()+".bak").exists(); }
    public synchronized boolean open() { return master != null; }
    private SecretKey deviceKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            if (exists()) throw new IOException("Keystore key missing");
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            generator.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    public synchronized void create(String password) throws Exception {
        if (exists()) throw new IllegalStateException("Vault already exists");
        master = Crypto.random(32);
        data = new JSONObject().put("schema", 1).put("account", new JSONObject())
            .put("keys", new JSONArray()).put("chats", new JSONArray());
        try { setPassword(password); } catch(Exception e) {lock();throw e;}
    }
    public synchronized void unlock(String password) throws Exception {
        lock();
        JSONObject outer = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        Cipher deviceCipher = Cipher.getInstance("AES/GCM/NoPadding");
        deviceCipher.init(Cipher.DECRYPT_MODE, deviceKey(), new GCMParameterSpec(128, Crypto.unb64(outer.getString("iv"))));
        JSONObject inner = new JSONObject(new String(deviceCipher.doFinal(Crypto.unb64(outer.getString("ciphertext"))), StandardCharsets.UTF_8));
        JSONObject h = inner.getJSONObject("header");
        if (h.getInt("iterations") != Crypto.ITERATIONS) throw new IOException("Unsupported KDF");
        byte[] derived = Crypto.pbkdf(password, Crypto.unb64(h.getString("salt")), Crypto.ITERATIONS);
        byte[] unlocked = null;
        try {
            unlocked = Crypto.decrypt(derived, h.getJSONObject("wrapped"));
            JSONObject decoded = new JSONObject(new String(Crypto.decrypt(unlocked, inner.getJSONObject("data")), StandardCharsets.UTF_8));
            validate(decoded);
            master = unlocked; data = decoded; header = h;
        } catch (Exception e) { if (unlocked != null) Arrays.fill(unlocked, (byte) 0); throw e; }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    public synchronized void setPassword(String password) throws Exception {
        if (password.length() < 12) throw new IllegalArgumentException("Password must contain at least 12 characters");
        byte[] salt = Crypto.random(32), derived = Crypto.pbkdf(password, salt, Crypto.ITERATIONS);
        JSONObject oldHeader = header;
        try {
            header = new JSONObject().put("salt", Crypto.b64(salt)).put("iterations", Crypto.ITERATIONS)
                .put("wrapped", Crypto.encrypt(derived, master));
            save();
        } catch (Exception e) { header = oldHeader; throw e; }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    public synchronized JSONObject snapshot() throws Exception {
        if (!open()) throw new IllegalStateException("Locked");
        return new JSONObject(data.toString());
    }
    public synchronized void replace(JSONObject next) throws Exception {
        validate(next);
        JSONObject old = data; data = new JSONObject(next.toString());
        try { save(); } catch (Exception e) { data = old; throw e; }
    }
    private void save() throws Exception {
        if (!open()) throw new IllegalStateException("Locked");
        JSONObject inner = new JSONObject().put("header", header).put("data", Crypto.encrypt(master, data.toString().getBytes(StandardCharsets.UTF_8)));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey());
        JSONObject outer = new JSONObject().put("version", 1).put("iv", Crypto.b64(cipher.getIV()))
            .put("ciphertext", Crypto.b64(cipher.doFinal(inner.toString().getBytes(StandardCharsets.UTF_8))));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(outer.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
    public synchronized void lock() {
        if (master != null) Arrays.fill(master, (byte) 0);
        master = null; data = null; header = null;
    }
    public static void validate(JSONObject d) throws Exception {
        if (d.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported account schema");
        JSONObject a = d.getJSONObject("account");
        if (a.length() > 0) { ChatApi.endpoint(a.getString("url")); a.getString("username"); a.getString("password"); }
        JSONArray keys = d.getJSONArray("keys"), chats = d.getJSONArray("chats");
        if (keys.length() > 1000 || chats.length() > 100) throw new IllegalArgumentException("Too many keys or chats");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i=0; i<keys.length(); i++) {
            JSONObject k = keys.getJSONObject(i);
            if (!ids.add(k.getString("id")) || (k.getString("name").trim().isEmpty() || k.getString("name").length()>100)
                || k.getString("phrase").length() < 12 || k.getString("phrase").length() > 1024) throw new IllegalArgumentException("Invalid key");
        }
        java.util.HashSet<String> roomNames = new java.util.HashSet<>();
        for (int i=0; i<chats.length(); i++) {
            JSONObject c = chats.getJSONObject(i); String name = c.getString("name");
            int length = name.codePointCount(0, name.length());
            if (length < 3 || length > 80 || !name.equals(name.trim()) || !roomNames.add(name)
                || !ids.contains(c.getString("keyId"))) throw new IllegalArgumentException("Invalid chat");
            c.getJSONArray("messages");
        }
    }
}
