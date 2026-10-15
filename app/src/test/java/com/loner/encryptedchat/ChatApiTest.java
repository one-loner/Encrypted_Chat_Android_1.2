package com.loner.encryptedchat;

import org.junit.Test;
import org.json.*;
import okhttp3.mockwebserver.*;
import static org.junit.Assert.*;

public class ChatApiTest {
    private MockResponse json(String body){return new MockResponse().setHeader("Content-Type","application/json").setBody(body);}
    @Test public void cookiesAndCsrfAreIsolatedPerRoom() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(json("{\"csrf\":\"token-a\"}").setHeader("Set-Cookie","PHPSESSID=session-a; Path=/; HttpOnly"));
            server.enqueue(json(new JSONObject().put("chatId",Crypto.hash("Room A")).toString()));
            server.enqueue(json("{\"csrf\":\"token-b\"}").setHeader("Set-Cookie","PHPSESSID=session-b; Path=/; HttpOnly"));
            server.enqueue(json(new JSONObject().put("chatId",Crypto.hash("Room B")).toString()));
            server.enqueue(json("{\"messages\":[]}"));server.enqueue(json("{\"messages\":[]}"));
            server.start();ChatApi a=new ChatApi(server.url("/index.php")),b=new ChatApi(server.url("/index.php"));
            JSONObject user=new JSONObject().put("username","alice").put("password","secret");
            try {
                a.connect(user,"Room A");b.connect(user,"Room B");a.messages(new JSONArray());b.messages(new JSONArray());
                server.takeRequest();RecordedRequest joinA=server.takeRequest();server.takeRequest();RecordedRequest joinB=server.takeRequest();
                RecordedRequest readA=server.takeRequest(),readB=server.takeRequest();
                assertEquals("token-a",joinA.getHeader("X-CSRF-Token"));assertEquals("token-b",joinB.getHeader("X-CSRF-Token"));
                assertEquals("PHPSESSID=session-a",readA.getHeader("Cookie"));assertEquals("PHPSESSID=session-b",readB.getHeader("Cookie"));
                assertEquals("Room A",new JSONObject(joinA.getBody().readUtf8()).getString("chatName"));
            }finally{a.close();b.close();}
        }
    }
    @Test public void fileEnvelopeIsFetchedAndCached() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(json("{\"csrf\":\"token\"}"));server.enqueue(json(new JSONObject().put("chatId",Crypto.hash("Room A")).toString()));
            String record="{\"messages\":[{\"id\":\"m1\",\"fileId\":\"f1\"}]}";
            server.enqueue(json(record));server.enqueue(json("{\"iv\":\"aQ==\",\"ciphertext\":\"Yw==\"}"));server.enqueue(json(record));
            server.start();ChatApi api=new ChatApi(server.url("/index.php"));
            try {
                api.connect(new JSONObject().put("username","a").put("password","b"),"Room A");
                JSONArray cached=api.messages(new JSONArray());assertTrue(cached.getJSONObject(0).has("envelope"));
                assertEquals(cached.toString(),api.messages(cached).toString());assertEquals(5,server.getRequestCount());
            }finally{api.close();}
        }
    }
    @Test public void sessionRevocationRaisesAuthError() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(json("{\"error\":\"Session revoked\"}").setResponseCode(401));server.start();ChatApi api=new ChatApi(server.url("/index.php"));
            try{api.messages(new JSONArray());fail();}catch(ChatApi.AuthException e){assertEquals("Session revoked",e.getMessage());}finally{api.close();}
        }
    }
    @Test public void httpsEndpointNormalization() {
        assertEquals("https://chat.example/ec8/index.php",ChatApi.endpoint("https://chat.example/ec8/web.html").toString());
        assertEquals("https://chat.example/ec8/index.php",ChatApi.endpoint("https://chat.example/ec8/").toString());
        try{ChatApi.endpoint("http://chat.example/");fail();}catch(IllegalArgumentException expected){}
        try{ChatApi.endpoint("https://a:b@chat.example/");fail();}catch(IllegalArgumentException expected){}
    }
}
