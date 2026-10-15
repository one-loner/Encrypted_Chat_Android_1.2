package com.loner.encryptedchat;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public class MainActivity extends Activity {
    private static final int EXPORT=11, IMPORT=12, ATTACH=13, DOWNLOAD=14, QR_IMAGE=15;
    private static final int MAX_BACKUP=100*1024*1024, MAX_FILE=5*1024*1024;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicInteger epoch=new AtomicInteger();
    private final Map<String,ChatApi> sessions=new ConcurrentHashMap<>();
    private final Map<String,byte[]> derived=new ConcurrentHashMap<>();
    private final Map<String,JSONArray> decoded=new ConcurrentHashMap<>();
    private final Map<String,String> states=new ConcurrentHashMap<>();
    private final Set<String> sending=ConcurrentHashMap.newKeySet();
    private final Set<String> revoked=ConcurrentHashMap.newKeySet();
    private final Map<String,RoomView> roomViews=new HashMap<>();
    private final Map<String,String> drafts=new HashMap<>();
    private final java.util.List<Dialog> dialogs=new java.util.ArrayList<>();
    private Vault vault;
    private LinearLayout root, body, nav;
    private TextView status;
    private ViewPager pager;
    private JSONArray rooms=new JSONArray();
    private String page="chats", selectedRoom="";
    private boolean active, polling, external;
    private int pendingJobs, failures;
    private long nextUnlock;
    private byte[] pendingBytes;
    private String pendingImportPassword, attachmentRoom, attachmentText;
    private final Runnable externalTimeout=() -> { if (external) lock(); };
    private final Runnable tick=new Runnable() {
        public void run() {
            if (!active || !vault.open() || external) return;
            if (!polling && pendingJobs==0) poll();
            handler.postDelayed(this,2000);
        }
    };
    @Override protected void attachBaseContext(Context base) {
        String lang=base.getSharedPreferences("preferences",MODE_PRIVATE).getString("language","");
        if (!lang.isEmpty()) {
            Configuration config=new Configuration(base.getResources().getConfiguration());
            config.setLocale(new Locale(lang)); base=base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        vault=new Vault(this); showGate();
    }
    @Override protected void onResume() {
        super.onResume(); active=true;
        handler.removeCallbacks(tick); if (vault.open()) handler.post(tick);
    }
    @Override protected void onStop() {
        super.onStop(); active=false; handler.removeCallbacks(tick);
        if (!external) lock();
    }
    @Override protected void onDestroy() {
        epoch.incrementAndGet(); closeSessions(); vault.lock();
        handler.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value,int size) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(27,45,41));
        t.setPadding(dp(8),dp(8),dp(8),dp(8)); t.setSaveEnabled(false); return t;
    }
    private Button button(int label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b;
    }
    private EditText field(int hint,boolean secret,String value) {
        EditText e=new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setSaveEnabled(false);
        e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);
        if (Build.VERSION.SDK_INT >= 26) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return e;
    }
    private void scrollInto(LinearLayout target,View content) {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        target.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void frame(String title,boolean navigation) {
        root=column(); root.setBackgroundColor(Color.rgb(243,246,245));
        root.setPadding(dp(12),dp(8),dp(12),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(dp(12)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),
                dp(12)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView heading=text(title,26); heading.setTypeface(null,Typeface.BOLD); root.addView(heading);
        status=text("",12); root.addView(status);
        body=column(); root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if (navigation) {
            nav=row(); root.addView(nav);
            nav.addView(button(R.string.chats,()->{page="chats";showHome();}),new LinearLayout.LayoutParams(0,-2,1));
            nav.addView(button(R.string.keys,()->{page="keys";showHome();}),new LinearLayout.LayoutParams(0,-2,1));
            nav.addView(button(R.string.settings,()->{page="settings";showHome();}),new LinearLayout.LayoutParams(0,-2,1));
        }
        setContentView(root); root.requestApplyInsets();
    }
    private interface Job { void run() throws Exception; }
    private void work(Job job,Runnable success) {
        final int ticket=epoch.get(); pendingJobs++;
        if (status!=null) status.setText(R.string.wait);
        io.execute(()->{
            if (ticket!=epoch.get()) return;
            Exception failure=null;
            try { job.run(); } catch (Exception e) { failure=e; }
            final Exception result=failure;
            handler.post(()->{
                if (ticket!=epoch.get() || isDestroyed()) return;
                pendingJobs=Math.max(0,pendingJobs-1);
                if (status!=null) status.setText("");
                if (result!=null) error(result.getMessage()); else if (success!=null) success.run();
            });
        });
    }
    private void error(String message) {
        if (!active) return;
        remember(new AlertDialog.Builder(this).setTitle(R.string.error).setMessage(message==null?getString(R.string.error):message)
            .setPositiveButton(R.string.ok,null).show());
    }
    private void toast(int message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }
    private JSONObject snapshot() {
        try { return vault.snapshot(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void showGate() {
        roomViews.clear(); pager=null;
        boolean create=!vault.exists(); frame(getString(create?R.string.create_vault:R.string.app_name),false);
        LinearLayout form=column(); scrollInto(body,form);
        if (create) form.addView(text(getString(R.string.vault_hint),16));
        EditText password=field(R.string.password,true,""); form.addView(password);
        EditText confirm=field(R.string.repeat_password,true,""); if (create) form.addView(confirm);
        Button submit=button(create?R.string.create:R.string.unlock,()->{}); form.addView(submit);
        submit.setOnClickListener(v->{
            if (SystemClock.elapsedRealtime()<nextUnlock || pendingJobs>0) { toast(R.string.wait); return; }
            String p=password.getText().toString();
            if (create && (p.length()<12 || !p.equals(confirm.getText().toString()))) { error(getString(R.string.password_rule)); return; }
            password.setText(""); confirm.setText("");
            work(()->{
                try { if (create) vault.create(p); else vault.unlock(p); }
                catch (Exception e) { vault.lock(); throw new IOException(getString(R.string.unlock_error)); }
            },()->{failures=0;showHome();handler.removeCallbacks(tick);handler.post(tick);});
            failures++; nextUnlock=SystemClock.elapsedRealtime()+Math.min(30000,failures*1000L);
        });
    }
    private void closeSessions() {
        for (ChatApi api:sessions.values()) api.close(); sessions.clear();
        for (byte[] key:derived.values()) Arrays.fill(key,(byte)0); derived.clear();
        revoked.clear(); states.clear(); decoded.clear(); sending.clear();
    }
    private void remember(Dialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> { dialogs.remove(dialog); if(dialog.getWindow()!=null) clearInputs(dialog.getWindow().getDecorView()); });
    }
    private void clearInputs(View view) {
        if(view instanceof EditText) ((EditText)view).setText("");
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearInputs(group.getChildAt(i)); }
    }
    private void lock() {
        for(Dialog d:new ArrayList<>(dialogs)) d.dismiss(); dialogs.clear();
        epoch.incrementAndGet(); pendingJobs=0; polling=false;
        closeSessions(); vault.lock();
        if(!io.isShutdown()) io.execute(()->{closeSessions();vault.lock();});
        drafts.clear(); roomViews.clear();
        if (pendingBytes!=null) Arrays.fill(pendingBytes,(byte)0); pendingBytes=null;
        pendingImportPassword=null; attachmentText=null; attachmentRoom=null;
        handler.removeCallbacks(tick); handler.removeCallbacks(externalTimeout);
        external=false; showGate();
    }
    private void showHome() {
        if (!vault.open()) { showGate(); return; }
        roomViews.clear(); pager=null;
        frame(getString(R.string.app_name),true);
        if ("keys".equals(page)) showKeys(); else if ("settings".equals(page)) showSettings(); else showChats();
    }
    private void showChats() {
        JSONObject d=snapshot(); rooms=d.optJSONArray("chats");
        LinearLayout bar=row(); bar.addView(button(R.string.add_chat,this::addChat)); body.addView(bar);
        if (rooms.length()==0) { body.addView(text(getString(R.string.empty_chats),18)); return; }
        HorizontalScrollView strip=new HorizontalScrollView(this); LinearLayout tabs=row(); strip.addView(tabs); body.addView(strip);
        pager=new ViewPager(this); pager.setId(View.generateViewId());
        body.addView(pager,new LinearLayout.LayoutParams(-1,0,1));
        pager.setAdapter(new PagerAdapter() {
            public int getCount() { return rooms.length(); }
            public boolean isViewFromObject(View view,Object object) { return view==object; }
            public Object instantiateItem(ViewGroup container,int position) {
                JSONObject c=rooms.optJSONObject(position); RoomView rv=new RoomView(c.optString("name"));
                roomViews.put(rv.name,rv); container.addView(rv.layout); rv.refresh(); return rv.layout;
            }
            public void destroyItem(ViewGroup container,int pos,Object object) {
                roomViews.remove(rooms.optJSONObject(pos).optString("name")); container.removeView((View)object);
            }
        });
        for (int i=0;i<rooms.length();i++) {
            final int index=i; String name=rooms.optJSONObject(i).optString("name");
            Button tab=new Button(this); tab.setText(name); tab.setAllCaps(false); tab.setOnClickListener(v->pager.setCurrentItem(index,true)); tabs.addView(tab);
        }
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            public void onPageSelected(int pos) { selectedRoom=rooms.optJSONObject(pos).optString("name"); }
        });
        for (int i=0;i<rooms.length();i++) if (rooms.optJSONObject(i).optString("name").equals(selectedRoom)) pager.setCurrentItem(i,false);
        selectedRoom=rooms.optJSONObject(pager.getCurrentItem()).optString("name");
    }
    private JSONObject chat(JSONObject d,String name) throws Exception {
        JSONArray c=d.getJSONArray("chats"); for (int i=0;i<c.length();i++) if (c.getJSONObject(i).getString("name").equals(name)) return c.getJSONObject(i);
        throw new IOException(getString(R.string.chat_invalid));
    }
    private JSONObject key(JSONObject d,String id) throws Exception {
        JSONArray keys=d.getJSONArray("keys"); for (int i=0;i<keys.length();i++) if (keys.getJSONObject(i).getString("id").equals(id)) return keys.getJSONObject(i);
        throw new IOException(getString(R.string.key_required));
    }
    private byte[] roomKey(JSONObject d,JSONObject chat) throws Exception {
        String name=chat.getString("name"); byte[] raw=derived.get(name);
        if (raw==null) { raw=Crypto.roomKey(key(d,chat.getString("keyId")).getString("phrase"),name); derived.put(name,raw); }
        return raw;
    }
    private ChatApi session(JSONObject d,String name) throws Exception {
        if (revoked.contains(name)) throw new ChatApi.AuthException(getString(R.string.revoked));
        ChatApi api=sessions.get(name);
        if (api==null) {
            api=new ChatApi(d.getJSONObject("account").getString("url")); sessions.put(name,api);
            try { api.connect(d.getJSONObject("account"),name); }
            catch (Exception e) { sessions.remove(name); api.close(); throw e; }
        }
        return api;
    }
    private void sessionError(String name,Exception e) {
        ChatApi old=sessions.remove(name); if (old!=null) old.close();
        if (e instanceof ChatApi.AuthException) { revoked.add(name); decoded.remove(name); states.put(name,getString(R.string.revoked)); }
        else states.put(name,getString(R.string.offline)+" · "+(e.getMessage()==null?"":e.getMessage()));
    }
    private JSONArray decryptMessages(byte[] key,JSONArray records) throws Exception {
        JSONArray out=new JSONArray();
        for (int i=0;i<records.length();i++) {
            JSONObject record=records.getJSONObject(i), item=new JSONObject().put("sender",record.optString("sender")).put("createdAt",record.optString("createdAt"));
            try {
                String plain=new String(Crypto.decrypt(key,record.getJSONObject("envelope")),StandardCharsets.UTF_8);
                JSONObject payload;
                try { payload=new JSONObject(plain); } catch (JSONException e) { payload=new JSONObject().put("kind","text").put("text",plain); }
                item.put("payload",payload);
            } catch (Exception e) { item.put("error",true); }
            out.put(item);
        }
        return out;
    }
    private void poll() {
        if (!vault.open()) return; polling=true;
        final int ticket=epoch.get();
        io.execute(()->{
            try {
                JSONObject d=vault.snapshot(); JSONArray chats=d.getJSONArray("chats"); boolean changed=false;
                for (int i=0;i<chats.length();i++) {
                    if (ticket!=epoch.get()) return;
                    JSONObject c=chats.getJSONObject(i); String name=c.getString("name");
                    if (revoked.contains(name)) continue;
                    byte[] key=roomKey(d,c);
                    if (!decoded.containsKey(name) && ticket==epoch.get()) decoded.put(name,decryptMessages(key,c.getJSONArray("messages")));
                    try {
                        JSONArray records=session(d,name).messages(c.getJSONArray("messages"));
                        if (ticket!=epoch.get()) return;
                        if (!records.toString().equals(c.getJSONArray("messages").toString())) {
                            c.put("messages",records); changed=true; decoded.put(name,decryptMessages(key,records));
                        }
                        states.put(name,getString(R.string.connected));
                    } catch (Exception e) { if (ticket==epoch.get()) sessionError(name,e); }
                }
                if (changed && ticket==epoch.get()) vault.replace(d);
            } catch (Exception e) { if (ticket==epoch.get()) states.put(selectedRoom,getString(R.string.offline)); }
            finally { handler.post(()->{ if (ticket==epoch.get()) { polling=false; for (RoomView v:roomViews.values()) v.refresh(); } }); }
        });
    }
    private final class RoomView {
        final String name; final LinearLayout layout=column(), messages=column();
        final TextView connection=text("",12); final EditText input=field(R.string.message,false,"");
        final ScrollView scroll=new ScrollView(MainActivity.this); JSONArray lastContent; boolean lastRevoked;
        RoomView(String roomName) {
            name=roomName; input.setSingleLine(false); input.setMaxLines(4); input.setText(drafts.get(name));
            input.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){drafts.put(name,s.toString());}
                public void afterTextChanged(android.text.Editable e){}
            });
            LinearLayout top=row(); top.addView(connection,new LinearLayout.LayoutParams(0,-2,1));
            top.addView(button(R.string.reconnect,()->{
                work(()->{ChatApi old=sessions.remove(name);if(old!=null)old.close();revoked.remove(name);states.remove(name);},()->{if(!polling)poll();});
            })); layout.addView(top);
            scroll.addView(messages); layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            layout.addView(input); LinearLayout controls=row();
            controls.addView(button(R.string.attach,()->{
                attachmentRoom=name;attachmentText=input.getText().toString(); launchDocument(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),ATTACH);
            }),new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(button(R.string.send,()->{
                String value=input.getText().toString(); if (!value.trim().isEmpty()) {
                    try { sendPayload(name,new JSONObject().put("kind","text").put("text",value),value); } catch(Exception e) {error(e.getMessage());}
                }
            }),new LinearLayout.LayoutParams(0,-2,1)); layout.addView(controls);
            layout.addView(button(R.string.close_chat,()->removeChat(name)));
        }
        void refresh() {
            connection.setText(states.containsKey(name)?states.get(name):getString(R.string.offline));
            JSONArray content=revoked.contains(name)?null:decoded.get(name);
            boolean blocked=revoked.contains(name); if(content==lastContent && blocked==lastRevoked && messages.getChildCount()>0) return;
            lastContent=content; lastRevoked=blocked; int previousY=scroll.getScrollY(); boolean bottom=scroll.getChildCount()==0 || messages.getHeight()-scroll.getHeight()-previousY<dp(80);
            messages.removeAllViews();
            if (content==null || content.length()==0) messages.addView(text(getString(R.string.no_messages),16));
            else for (int i=0;i<content.length();i++) {
                JSONObject m=content.optJSONObject(i); LinearLayout card=column();
                card.setPadding(dp(8),dp(4),dp(8),dp(4));
                card.setBackgroundColor(i%2==0?Color.WHITE:Color.rgb(229,240,236));
                card.addView(text(m.optString("sender")+" · "+m.optString("createdAt"),12));
                if (m.optBoolean("error")) card.addView(text(getString(R.string.undecryptable),14));
                else {
                    JSONObject payload=m.optJSONObject("payload"); TextView t=text(payload.optString("text"),17); t.setTextIsSelectable(true); card.addView(t);
                    if ("file".equals(payload.optString("kind"))) {
                        card.addView(text(payload.optString("name"),14));
                        card.addView(button(R.string.download,()->{
                            try {
                                byte[] bytes=Crypto.unb64(payload.getString("data"));
                                if(bytes.length>MAX_FILE)throw new IOException(getString(R.string.file_large));
                                pendingBytes=bytes; createDocument(payload.optString("mime","application/octet-stream"),safeFilename(payload.optString("name","attachment")),DOWNLOAD);
                            } catch(Exception e) {error(e.getMessage());}
                        }));
                    }
                }
                messages.addView(card);
            }
            if(bottom)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); else scroll.post(()->scroll.scrollTo(0,previousY));
        }
    }
    private void sendPayload(String name,JSONObject payload,String sentDraft) {
        if(!sending.add(name)){toast(R.string.wait);return;}
        final int ticket=epoch.get();
        work(()->{
            try {
            JSONObject d=vault.snapshot(); JSONObject c=chat(d,name); ChatApi api;
            try { api=session(d,name); } catch(Exception e) {sessionError(name,e);throw e;}
            JSONObject envelope=Crypto.encrypt(roomKey(d,c),payload.toString().getBytes(StandardCharsets.UTF_8));
            try { api.send(envelope); }
            catch (Exception e) { sessionError(name,e); throw new IOException(getString(R.string.send_uncertain),e); }
            if (ticket!=epoch.get()) return;
            } catch(Exception e) {sending.remove(name);throw e;}
        },()->{
            sending.remove(name);
            RoomView rv=roomViews.get(name);
            if (rv!=null && rv.input.getText().toString().equals(sentDraft)) rv.input.setText("");
            else if (sentDraft.equals(drafts.get(name))) drafts.remove(name);
            toast(R.string.sent); if (!polling) poll();
        });
    }
    private void addChat() {
        JSONObject d=snapshot();
        if (d.optJSONObject("account").length()==0) {error(getString(R.string.account_required));return;}
        JSONArray keys=d.optJSONArray("keys"); if(keys.length()==0){error(getString(R.string.key_required));return;}
        LinearLayout form=column(); EditText name=field(R.string.chat_name,false,"");form.addView(name);
        Spinner spinner=new Spinner(this); String[] names=new String[keys.length()]; for(int i=0;i<names.length;i++)names[i]=keys.optJSONObject(i).optString("name");
        spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));form.addView(spinner);
        dialog(R.string.add_chat,form,R.string.open_chat,()->{
            String value=name.getText().toString().trim(); String id=keys.optJSONObject(spinner.getSelectedItemPosition()).optString("id");
            work(()->{
                JSONObject current=vault.snapshot();JSONArray chats=current.getJSONArray("chats");
                chats.put(new JSONObject().put("name",value).put("keyId",id).put("messages",new JSONArray()));
                try {Vault.validate(current);}catch(Exception e){throw new IOException(getString(R.string.chat_invalid));}
                vault.replace(current);
            },()->{selectedRoom=value;page="chats";showHome();if(!polling)poll();});
        });
    }
    private void removeChat(String name) {
        remember(new AlertDialog.Builder(this).setTitle(R.string.close_chat).setMessage(R.string.close_hint).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.delete,(v,w)->work(()->{
                JSONObject d=vault.snapshot();JSONArray chats=d.getJSONArray("chats");
                for(int i=0;i<chats.length();i++)if(chats.getJSONObject(i).getString("name").equals(name)){chats.remove(i);break;}
                vault.replace(d); ChatApi old=sessions.remove(name);if(old!=null)old.close();byte[] raw=derived.remove(name);if(raw!=null)Arrays.fill(raw,(byte)0);
                decoded.remove(name);states.remove(name);revoked.remove(name);
            },()->{drafts.remove(name);showHome();})).show());
    }
    private void dialog(int title,View view,int action,Runnable run) {
        ScrollView scroll=new ScrollView(this); scroll.setPadding(dp(16),0,dp(16),0);scroll.addView(view);
        remember(new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(action,(d,w)->run.run()).show());
    }
    private void showKeys() {
        LinearLayout controls=row();controls.addView(button(R.string.new_key,this::newKey));controls.addView(button(R.string.scan_qr,()->{
            external=true;handler.postDelayed(externalTimeout,60000);
            IntentIntegrator scanner=new IntentIntegrator(this);scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            scanner.setPrompt(getString(R.string.scan_qr));scanner.setBeepEnabled(false);scanner.setOrientationLocked(false);scanner.initiateScan();
        }));body.addView(controls);
        body.addView(button(R.string.paste_qr,()->{
            LinearLayout f=column();EditText json=field(R.string.qr_text,true,"");f.addView(json);
            dialog(R.string.import_key,f,R.string.import_key,()->importKey(json.getText().toString()));
        }));
        LinearLayout list=column();scrollInto(body,list);JSONArray keys=snapshot().optJSONArray("keys");
        if(keys.length()==0)list.addView(text(getString(R.string.key_empty),16));
        for(int i=0;i<keys.length();i++) {
            JSONObject k=keys.optJSONObject(i); list.addView(text(k.optString("name"),22)); LinearLayout actions=row();list.addView(actions);
            actions.addView(button(R.string.qr,()->showQr(k)));actions.addView(button(R.string.rename,()->renameKey(k)));
            list.addView(button(R.string.reveal,()->remember(new AlertDialog.Builder(this).setTitle(k.optString("name"))
                .setMessage(getString(R.string.qr_hint)+"\n\n"+k.optString("phrase")).setPositiveButton(R.string.ok,null).show())));
            list.addView(button(R.string.delete,()->deleteKey(k)));
        }
    }
    private void newKey() {
        LinearLayout form=column(); form.addView(text(getString(R.string.key_hint),14));
        EditText name=field(R.string.key_name,false,"");EditText phrase=field(R.string.phrase,true,"");form.addView(name);form.addView(phrase);
        dialog(R.string.new_key,form,R.string.save,()->{
            String n=name.getText().toString().trim(),p=phrase.getText().toString();
            if(n.isEmpty() || n.length()>100 || (!p.isEmpty() && p.length()<12) || p.length()>1024){error(getString(R.string.key_invalid));return;}
            addKey(n,p.isEmpty()?Crypto.b64(Crypto.random(32)):p);
        });
    }
    private void addKey(String name,String phrase) {
        work(()->{
            JSONObject d=vault.snapshot();d.getJSONArray("keys").put(new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("phrase",phrase));vault.replace(d);
        },this::showHome);
    }
    private void renameKey(JSONObject k) {
        LinearLayout form=column();EditText n=field(R.string.key_name,false,k.optString("name"));form.addView(n);
        dialog(R.string.rename,form,R.string.save,()->{
            String name=n.getText().toString().trim();if(name.isEmpty() || name.length()>100){error(getString(R.string.key_invalid));return;}
            work(()->{JSONObject d=vault.snapshot();key(d,k.getString("id")).put("name",name);vault.replace(d);},this::showHome);
        });
    }
    private void deleteKey(JSONObject k) {
        remember(new AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(k.optString("name")).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.delete,(dialog,which)->work(()->{
                JSONObject d=vault.snapshot();JSONArray c=d.getJSONArray("chats");
                for(int i=0;i<c.length();i++)if(c.getJSONObject(i).getString("keyId").equals(k.getString("id")))throw new IOException(getString(R.string.key_in_use));
                JSONArray keys=d.getJSONArray("keys");for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getString("id").equals(k.getString("id"))){keys.remove(i);break;}vault.replace(d);
            },this::showHome)).show());
    }
    private void importKey(String raw) {
        try {
            if(raw.length()>4096)throw new IllegalArgumentException();JSONObject qr=new JSONObject(raw);
            if(!"encrypted-chat-key".equals(qr.getString("format")) || qr.getInt("version")!=1 || !"phrase".equals(qr.getString("type")))throw new IllegalArgumentException();
            String name=qr.getString("name").trim(),phrase=qr.getString("phrase");
            if(name.isEmpty() || name.length()>100 || phrase.length()<12 || phrase.length()>1024)throw new IllegalArgumentException();
            remember(new AlertDialog.Builder(this).setTitle(R.string.import_key).setMessage(name+"\n\n"+getString(R.string.qr_hint))
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.import_key,(d,w)->addKey(name,phrase)).show());
        }catch(Exception e){error(getString(R.string.qr_invalid));}
    }
    private void showQr(JSONObject k) {
        try {
            JSONObject data=new JSONObject().put("format","encrypted-chat-key").put("version",1).put("type","phrase")
                .put("name",k.getString("name")).put("phrase",k.getString("phrase"));
            Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");hints.put(EncodeHintType.MARGIN,4);
            BitMatrix matrix=new MultiFormatWriter().encode(data.toString(),BarcodeFormat.QR_CODE,768,768,hints);
            Bitmap bitmap=Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888);
            for(int y=0;y<768;y++)for(int x=0;x<768;x++)bitmap.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);
            LinearLayout form=column();form.addView(text(getString(R.string.qr_hint),14));
            ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);form.addView(image,new LinearLayout.LayoutParams(-1,dp(300)));
            form.addView(button(R.string.export_qr,()->{
                ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);pendingBytes=out.toByteArray();createDocument("image/png","chat-key.png",QR_IMAGE);
            }));dialog(R.string.qr,form,R.string.ok,()->{});
        }catch(Exception e){error(getString(R.string.qr_invalid));}
    }
    private void showSettings() {
        LinearLayout form=column();scrollInto(body,form);
        JSONObject account=snapshot().optJSONObject("account");form.addView(text(account.optString("username")+"\n"+account.optString("url"),16));
        form.addView(button(R.string.account,this::editAccount));form.addView(button(R.string.language,this::language));
        form.addView(button(R.string.change_password,this::changePassword));form.addView(button(R.string.export,this::exportAccount));
        form.addView(button(R.string.restore,this::importAccount));form.addView(button(R.string.lock,this::lock));
    }
    private void editAccount() {
        JSONObject current=snapshot().optJSONObject("account");LinearLayout form=column();form.addView(text(getString(R.string.account_hint),14));
        EditText url=field(R.string.server_url,false,current.optString("url")),user=field(R.string.username,false,current.optString("username")),password=field(R.string.server_password,true,current.optString("password"));
        url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);form.addView(url);form.addView(user);form.addView(password);
        dialog(R.string.account,form,R.string.save_connect,()->{
            String endpoint=url.getText().toString().trim(),username=user.getText().toString().trim(),p=password.getText().toString();
            work(()->{
                String normalized=ChatApi.endpoint(endpoint).toString();JSONObject account=new JSONObject().put("url",normalized).put("username",username).put("password",p);
                ChatApi test=new ChatApi(normalized);try{test.connect(account,"AndroidConnectionCheck");}finally{test.close();}
                JSONObject d=vault.snapshot();JSONObject old=d.getJSONObject("account");
                if(!normalized.equals(old.optString("url")) || !username.equals(old.optString("username"))) {
                    JSONArray chats=d.getJSONArray("chats");for(int i=0;i<chats.length();i++)chats.getJSONObject(i).put("messages",new JSONArray());
                }
                d.put("account",account);vault.replace(d);closeSessions();
            },()->{showHome();if(!polling)poll();});
        });
    }
    private void language() {
        String[] labels={getString(R.string.system_language),"English","Русский"};String[] codes={"","en","ru"};
        remember(new AlertDialog.Builder(this).setTitle(R.string.language).setItems(labels,(d,i)->{
            getSharedPreferences("preferences",MODE_PRIVATE).edit().putString("language",codes[i]).apply();lock();recreate();
        }).show());
    }
    private void changePassword() {
        LinearLayout form=column();EditText old=field(R.string.current_password,true,""),p=field(R.string.password,true,""),repeat=field(R.string.repeat_password,true,"");
        form.addView(old);form.addView(p);form.addView(repeat);
        dialog(R.string.change_password,form,R.string.save,()->{
            String previous=old.getText().toString(),next=p.getText().toString();
            if(next.length()<12 || !next.equals(repeat.getText().toString())){error(getString(R.string.password_rule));return;}
            work(()->{
                Vault check=new Vault(this);try{check.unlock(previous);}catch(Exception e){throw new IOException(getString(R.string.unlock_error));}finally{check.lock();}
                vault.setPassword(next);
            },()->toast(R.string.saved));
        });
    }
    private void exportAccount() {
        LinearLayout form=column();form.addView(text(getString(R.string.backup_hint),14));
        EditText p=field(R.string.export_password,true,""),r=field(R.string.repeat_password,true,"");form.addView(p);form.addView(r);
        dialog(R.string.export,form,R.string.export,()->{
            String password=p.getText().toString();if(password.length()<12 || !password.equals(r.getText().toString())){error(getString(R.string.password_rule));return;}
            work(()->{
                byte[] bytes=Crypto.exportAccount(vault.snapshot(),password).toString(2).getBytes(StandardCharsets.UTF_8);
                if(bytes.length>MAX_BACKUP)throw new IOException(getString(R.string.export_failed));pendingBytes=bytes;
            },()->createDocument("application/json","encrypted-chat-account.json",EXPORT));
        });
    }
    private void importAccount() {
        LinearLayout form=column();form.addView(text(getString(R.string.import_hint),14));EditText p=field(R.string.import_password,true,"");form.addView(p);
        dialog(R.string.restore,form,R.string.restore,()->{
            pendingImportPassword=p.getText().toString();launchDocument(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),IMPORT);
        });
    }
    private void createDocument(String mime,String filename,int request) {
        launchDocument(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename),request);
    }
    private void launchDocument(Intent intent,int request) {
        external=true;handler.removeCallbacks(tick);handler.removeCallbacks(externalTimeout);handler.postDelayed(externalTimeout,60000);
        try {startActivityForResult(intent,request);}catch(ActivityNotFoundException e){external=false;handler.removeCallbacks(externalTimeout);error(e.getMessage());}
    }
    private String safeFilename(String raw) { return raw.replaceAll("[\\\\/\\p{Cntrl}]","_"); }
    private byte[] readUri(Uri uri,int limit) throws Exception {
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Cannot open file");byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException(getString(limit==MAX_FILE?R.string.file_large:R.string.export_failed));out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    private String uriName(Uri uri) {
        try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null && c.moveToFirst())return safeFilename(c.getString(0));
        }catch(Exception ignored){}return "attachment";
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);external=false;handler.removeCallbacks(externalTimeout);
        IntentResult qr=IntentIntegrator.parseActivityResult(request,result,data);
        if(qr!=null){if(vault.open() && qr.getContents()!=null)importKey(qr.getContents());return;}
        if(!vault.open())return;
        handler.removeCallbacks(tick);handler.post(tick);
        if(result!=RESULT_OK || data==null || data.getData()==null){pendingBytes=null;pendingImportPassword=null;attachmentRoom=null;attachmentText=null;return;}
        Uri uri=data.getData();
        if(request==EXPORT || request==DOWNLOAD || request==QR_IMAGE) {
            byte[] bytes=pendingBytes;pendingBytes=null;if(bytes==null)return;
            work(()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot write file");out.write(bytes);}
                finally{Arrays.fill(bytes,(byte)0);}
            },()->toast(R.string.saved));
        }else if(request==IMPORT) {
            String password=pendingImportPassword;pendingImportPassword=null;
            work(()->{
                try {
                    JSONObject restored=Crypto.importAccount(new JSONObject(new String(readUri(uri,MAX_BACKUP),StandardCharsets.UTF_8)),password);
                    Vault.validate(restored);vault.replace(restored);closeSessions();
                }catch(Exception e){throw new IOException(getString(R.string.import_failed));}
            },()->{drafts.clear();showHome();toast(R.string.restored);if(!polling)poll();});
        }else if(request==ATTACH) {
            String name=attachmentRoom,value=attachmentText;attachmentRoom=null;attachmentText=null;
            if(name==null)return;
            final JSONObject[] payload=new JSONObject[1];
            work(()->{
                byte[] bytes=readUri(uri,MAX_FILE);String mime=getContentResolver().getType(uri);
                payload[0]=new JSONObject().put("kind","file").put("name",uriName(uri)).put("mime",mime==null?"application/octet-stream":mime)
                    .put("data",Crypto.b64(bytes)).put("text",value==null?"":value);Arrays.fill(bytes,(byte)0);
            },()->sendPayload(name,payload[0],value==null?"":value));
        }
    }
}
