package com.loner.encryptedchat;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** Each instance owns its cookies and CSRF token: PHP stores room in session. */
public final class ChatApi {
    public static final class AuthException extends IOException { public AuthException(String message) { super(message); } }
    private final OkHttpClient client;
    private final HttpUrl url;
    private String csrf = "";
    public static HttpUrl endpoint(String input) {
        HttpUrl u = HttpUrl.parse(input.trim());
        if (u == null || !u.isHttps() || !u.username().isEmpty() || !u.password().isEmpty()
            || u.query() != null || u.fragment() != null) throw new IllegalArgumentException("HTTPS URL required");
        HttpUrl.Builder b = u.newBuilder();
        if (u.encodedPath().endsWith("/")) b.addPathSegment("index.php");
        else if (u.encodedPath().endsWith("/web.html")) b.setPathSegment(u.pathSegments().size()-1, "index.php");
        else if (!u.encodedPath().endsWith("/index.php")) b.addPathSegment("index.php");
        return b.build();
    }
    public ChatApi(String server) { this(endpoint(server)); }
    ChatApi(HttpUrl endpoint) {
        url = endpoint;
        CookieJar jar = new CookieJar() {
            private final Map<String,Cookie> cookies = new HashMap<>();
            public synchronized void saveFromResponse(HttpUrl u, List<Cookie> values) {
                for (Cookie c : values) cookies.put(c.name()+":"+c.path(), c);
            }
            public synchronized List<Cookie> loadForRequest(HttpUrl u) {
                List<Cookie> out = new ArrayList<>();
                for (Cookie c : cookies.values()) if (c.expiresAt() > System.currentTimeMillis() && c.matches(u)) out.add(c);
                return out;
            }
        };
        client = new OkHttpClient.Builder().cookieJar(jar).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    }
    private JSONObject request(String action, JSONObject body, String id) throws Exception {
        HttpUrl.Builder u = url.newBuilder().addQueryParameter("action", action);
        if (id != null) u.addQueryParameter("id", id);
        Request.Builder r = new Request.Builder().url(u.build());
        if (!csrf.isEmpty()) r.header("X-CSRF-Token", csrf);
        if (body != null) r.post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")));
        try (Response response = client.newCall(r.build()).execute()) {
            if (response.body() == null) throw new IOException("Empty server response");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = response.body().byteStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size()+count > 12000000) throw new IOException("Server response too large");
                out.write(buffer, 0, count);
            }
            byte[] bytes = out.toByteArray();
            return parse(response, bytes);
        }
    }
    private JSONObject parse(Response response, byte[] bytes) throws Exception {
        JSONObject result = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (!response.isSuccessful()) {
            if (response.code()==401) throw new AuthException(result.optString("error", "401"));
            throw new IOException(result.optString("error", "HTTP " + response.code()));
        }
        if (result.has("csrf")) csrf = result.getString("csrf");
        return result;
    }
    public void connect(JSONObject account, String name) throws Exception {
        request("login", new JSONObject().put("username", account.getString("username")).put("password", account.getString("password")), null);
        JSONObject joined = request("join_room", new JSONObject().put("chatName", name), null);
        if (!Crypto.hash(name).equals(joined.getString("chatId"))) throw new IOException("Unexpected chat ID");
    }
    public JSONArray messages(JSONArray cached) throws Exception {
        JSONArray records = request("messages", null, null).getJSONArray("messages");
        Map<String, JSONObject> old = new HashMap<>();
        for (int i=0; i<cached.length(); i++) { JSONObject c=cached.getJSONObject(i); old.put(c.optString("id"), c); }
        for (int i=0; i<records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (record.has("fileId")) {
                JSONObject prior = old.get(record.optString("id"));
                if (prior != null && prior.optString("fileId").equals(record.getString("fileId")) && prior.has("envelope"))
                    record.put("envelope", prior.getJSONObject("envelope"));
                else record.put("envelope", request("file", null, record.getString("fileId")));
            } else record.put("envelope", new JSONObject().put("iv", record.getString("iv")).put("ciphertext", record.getString("ciphertext")));
        }
        return records;
    }
    public void send(JSONObject envelope) throws Exception { request("messages", envelope, null); }
    public void close() { csrf=""; client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
}
