package com.loner.encryptedchat;

import org.junit.Test;
import org.json.*;
import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class CryptoTest {
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString(); }
    @Test public void pbkdf2PublishedSha256Vector() throws Exception {
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",hex(Crypto.pbkdf("password","salt".getBytes(StandardCharsets.UTF_8),1)));
    }
    @Test public void unicodeRoomMatchesIndependentPythonVector() throws Exception {
        assertEquals("de2a0634e57ef0b8fbe53e9e64daeb94793affa92e62dc49938d4c602f81b91e",Crypto.hash("Тестовая комната"));
        assertEquals("19f0ab8ce0d1438fc9a41412aa2b0b2457765379229f446a9036bc4acb78ce84",hex(Crypto.roomKey("ключевая фраза 🔐 2026","Тестовая комната")));
    }
    @Test public void aes256GcmNistVectorIncludesWebCryptoTag() throws Exception {
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919",
            hex(Crypto.crypt(Cipher.ENCRYPT_MODE,new byte[32],new byte[12],new byte[16],null)));
    }
    @Test public void backupRoundTripIncludesEveryChatAndKey() throws Exception {
        JSONObject data=new JSONObject().put("schema",1).put("account",new JSONObject().put("username","alice").put("password","secret"))
            .put("keys",new JSONArray().put("key1").put("key2")).put("chats",new JSONArray().put("room1").put("room2"));
        JSONObject envelope=Crypto.exportAccount(data,"long backup password");
        assertFalse(envelope.toString().contains("alice"));
        assertEquals(data.toString(),Crypto.importAccount(envelope,"long backup password").toString());
        assertNotEquals(envelope.toString(),Crypto.exportAccount(data,"long backup password").toString());
    }
    @Test public void wrongPasswordAndTamperingAreRejected() throws Exception {
        JSONObject e=Crypto.exportAccount(new JSONObject().put("secret","value"),"long backup password");
        try{Crypto.importAccount(e,"wrong password");fail();}catch(javax.crypto.AEADBadTagException expected){}
        byte[] b=Crypto.unb64(e.getString("ciphertext"));b[0]^=1;e.put("ciphertext",Crypto.b64(b));
        try{Crypto.importAccount(e,"long backup password");fail();}catch(javax.crypto.AEADBadTagException expected){}
    }
    @Test public void unboundedKdfFromBackupIsRejected() throws Exception {
        JSONObject e=Crypto.exportAccount(new JSONObject(),"long backup password");e.put("iterations",Integer.MAX_VALUE);
        try{Crypto.importAccount(e,"long backup password");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void roomNameIsPartOfKeyDerivation() throws Exception {
        assertFalse(java.util.Arrays.equals(Crypto.roomKey("long common phrase","Room one"),Crypto.roomKey("long common phrase","Room two")));
    }
}
