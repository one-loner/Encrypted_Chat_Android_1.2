package com.loner.encryptedchat;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.UUID;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses real Android Keystore; isolated files never replace the user's vault. */
@RunWith(AndroidJUnit4.class)
public class VaultDeviceTest {
    private File dir;
    private Context context;
    @Before public void setup() {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir=new File(target.getNoBackupFilesDir(),"test-"+UUID.randomUUID());assertTrue(dir.mkdirs());
        context=new ContextWrapper(target){@Override public File getNoBackupFilesDir(){return dir;}};
    }
    @After public void cleanup(){for(File f:dir.listFiles())f.delete();dir.delete();}
    @Test public void encryptedStorageLocksAndReopens() throws Exception {
        Vault v=new Vault(context);v.create("strong local password");
        JSONObject d=v.snapshot();d.getJSONObject("account").put("url","https://example.org/index.php").put("username","alice").put("password","server-secret-123");
        d.getJSONArray("keys").put(new JSONObject().put("id","k1").put("name","Family").put("phrase","encryption-secret-123"));
        d.getJSONArray("chats").put(new JSONObject().put("name","Family room").put("keyId","k1").put("messages",new JSONArray()));
        v.replace(d);v.lock();assertFalse(v.open());
        try{v.snapshot();fail();}catch(IllegalStateException expected){}
        try{v.unlock("incorrect password");fail();}catch(Exception expected){assertFalse(v.open());}
        v.unlock("strong local password");assertEquals(d.toString(),v.snapshot().toString());v.lock();
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(new File(dir,"vault.enc"))){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        String disk=out.toString("UTF-8");assertFalse(disk.contains("alice"));assertFalse(disk.contains("server-secret"));assertFalse(disk.contains("encryption-secret"));
    }
    @Test public void passwordChangeInvalidatesOldPassword() throws Exception {
        Vault v=new Vault(context);v.create("old strong password");JSONObject original=v.snapshot();v.setPassword("new strong password");v.lock();
        try{v.unlock("old strong password");fail();}catch(Exception expected){assertFalse(v.open());}
        v.unlock("new strong password");assertEquals(original.toString(),v.snapshot().toString());v.lock();
    }
    @Test public void invalidReplacementPreservesExistingData() throws Exception {
        Vault v=new Vault(context);v.create("strong local password");JSONObject original=v.snapshot();
        JSONObject invalid=new JSONObject(original.toString());invalid.getJSONArray("chats").put(new JSONObject().put("name","Room name").put("keyId","missing").put("messages",new JSONArray()));
        try{v.replace(invalid);fail();}catch(Exception expected){}
        assertEquals(original.toString(),v.snapshot().toString());v.lock();
    }
    @Test public void exportCanBeRestoredWithDifferentLocalPassword() throws Exception {
        Vault v=new Vault(context);v.create("old local password");JSONObject account=v.snapshot();JSONObject backup=Crypto.exportAccount(account,"strong backup password");v.lock();
        v.unlock("old local password");v.setPassword("different local password");v.replace(Crypto.importAccount(backup,"strong backup password"));v.lock();
        v.unlock("different local password");assertEquals(account.toString(),v.snapshot().toString());v.lock();
    }
}
