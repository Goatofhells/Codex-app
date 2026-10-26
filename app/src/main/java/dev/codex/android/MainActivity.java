package dev.codex.android;

import android.app.*;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private CodexSession session;
    private ProjectFiles files;
    private LinearLayout messages, chatPage;
    private TextView status, attachment;
    private EditText input;
    private Button login, cancel, send, logout, fresh, project, permissions, chatTab, filesTab;
    private ScrollView scroll;
    private boolean authenticated, busy, ready, preview, welcome;
    private String loginId, attached;
    private final Map<String, TextView> replies = new HashMap<>();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        session = (CodexSession)getApplication();
        LinearLayout root=Ui.column(this); root.setBackgroundColor(Ui.BG); Ui.pad(root,20);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark=Ui.text(this,">_",20,Ui.BG); mark.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); mark.setGravity(Gravity.CENTER); mark.setBackground(Ui.surface(Ui.GREEN,Ui.dp(this,12)));
        header.addView(mark,new LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,42)));
        LinearLayout brand=Ui.column(this); brand.setPadding(Ui.dp(this,12),0,0,0);
        TextView name=Ui.text(this,"Codex",21,Ui.TEXT); name.setTypeface(Typeface.DEFAULT,Typeface.BOLD); brand.addView(name); brand.addView(Ui.text(this,"YOUR MOBILE WORKSPACE",9,Ui.MUTED));
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        login=Ui.button(this,"Sign in",true,v->startLogin()); header.addView(login);
        logout=Ui.button(this,"Sign out",false,v->observe(session.logout(),r->{authenticated=false; messages.removeAllViews(); replies.clear(); status.setText("Signed out"); showWelcome(); update();})); header.addView(logout);
        root.addView(header); Ui.gap(root,22);
        project=Ui.button(this,"▣  No project selected    ›",false,v->showFiles(true)); project.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); root.addView(project,new LinearLayout.LayoutParams(-1,-2));
        Ui.gap(root,12);
        LinearLayout state=new LinearLayout(this); state.setGravity(Gravity.CENTER_VERTICAL);
        status=Ui.text(this,"●  Starting private runtime…",12,Ui.MUTED); state.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        status.setOnClickListener(v->{ if(!ready&&!preview){ status.setText("●  Reconnecting…"); observe(session.restart(),this::account); } });
        fresh=Ui.button(this,"＋ New",false,v->{session.newChat(); messages.removeAllViews(); replies.clear(); attached=null; attachment.setVisibility(View.GONE); showWelcome();}); state.addView(fresh);
        cancel=Ui.button(this,"Cancel login",false,v->{if(loginId!=null)observe(session.cancelLogin(loginId),r->{loginId=null;update();});}); state.addView(cancel);
        root.addView(state); Ui.gap(root,14);
        LinearLayout tabs=new LinearLayout(this);
        chatTab=Ui.button(this,"Chat",false,v->showFiles(false)); filesTab=Ui.button(this,"Files & access",false,v->showFiles(true));
        tabs.addView(chatTab,new LinearLayout.LayoutParams(0,-2,1)); LinearLayout.LayoutParams tabp=new LinearLayout.LayoutParams(0,-2,1);tabp.leftMargin=Ui.dp(this,8);tabs.addView(filesTab,tabp);root.addView(tabs);Ui.gap(root,16);
        FrameLayout content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        chatPage=Ui.column(this);content.addView(chatPage,new FrameLayout.LayoutParams(-1,-1));
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        messages=Ui.column(this);scroll.addView(messages);chatPage.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        attachment=Ui.text(this,"",12,Ui.GREEN);attachment.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));attachment.setVisibility(View.GONE);attachment.setOnClickListener(v->{attached=null;attachment.setVisibility(View.GONE);});chatPage.addView(attachment);
        LinearLayout composer=Ui.column(this);Ui.pad(composer,12);composer.setBackground(Ui.surface(Ui.PANEL,Ui.dp(this,18)));
        input=new EditText(this);input.setTextColor(Ui.TEXT);input.setHintTextColor(Ui.MUTED);input.setTextSize(15);input.setHint("Ask Codex to build anything…");input.setBackgroundColor(Color.TRANSPARENT);input.setMinLines(2);input.setMaxLines(4);input.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,8));composer.addView(input,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(Ui.button(this,"＋",false,v->showFiles(true)));
        permissions=Ui.button(this,"Project access ▾",false,v->choosePermissions());LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,-2,1);pp.leftMargin=Ui.dp(this,6);pp.rightMargin=Ui.dp(this,8);tools.addView(permissions,pp);
        send=Ui.button(this,"↑",true,v->submit());send.setContentDescription("Send message");tools.addView(send,new LinearLayout.LayoutParams(Ui.dp(this,46),Ui.dp(this,46)));composer.addView(tools);chatPage.addView(composer);
        TextView footer=Ui.text(this,"Review changes before you use them.",10,Ui.MUTED);footer.setGravity(Gravity.CENTER);footer.setPadding(0,Ui.dp(this,8),0,0);chatPage.addView(footer);
        files=new ProjectFiles(this,text->{attached=text;attachment.setText("≡  "+text.substring(6,text.indexOf('\n'))+" attached  ·  tap to remove");attachment.setVisibility(View.VISIBLE);showFiles(false);input.requestFocus();},()->project.setText("▣  "+files.summary()+"    ›"));
        content.addView(files.panel,new FrameLayout.LayoutParams(-1,-1));project.setText("▣  "+files.summary()+"    ›");showFiles(false);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(Ui.dp(this,20),Ui.dp(this,16)+insets.getSystemWindowInsetTop(),Ui.dp(this,20),Ui.dp(this,12)+insets.getSystemWindowInsetBottom());return insets;});
        session.observer=(method,data)->runOnUiThread(()->event(method,data));
        String example=BuildConfig.DEBUG?getIntent().getStringExtra("preview"):null;
        preview="login".equals(example)||"chat".equals(example)||"files".equals(example);
        if(preview){
            authenticated="chat".equals(example);status.setText("●  UI preview · sample data");status.setTextColor(Color.rgb(235,190,115));
            if(authenticated){add("You\nBuild a simple habit tracker for Android.");add("Codex\nLet's give it a clean home screen, daily check-ins, and a weekly progress view.\n\nConnect your project in Files to start with your own code.");add("You\nStart with the home screen.");}
            else showWelcome();
            showFiles("files".equals(example));update();return;
        }
        showWelcome();update();observe(session.connect(),this::account);
    }
    private void showFiles(boolean visible){chatPage.setVisibility(visible?View.GONE:View.VISIBLE);if(files!=null)files.panel.setVisibility(visible?View.VISIBLE:View.GONE);chatTab.setTextColor(visible?Ui.MUTED:Ui.GREEN);filesTab.setTextColor(visible?Ui.GREEN:Ui.MUTED);}
    private void showWelcome(){
        if(messages.getChildCount()>0)return;welcome=true;
        LinearLayout hero=Ui.column(this);hero.setPadding(Ui.dp(this,8),Ui.dp(this,28),Ui.dp(this,8),Ui.dp(this,18));
        TextView eyebrow=Ui.text(this,"LET’S MAKE SOMETHING",10,Ui.GREEN);eyebrow.setLetterSpacing(.12f);hero.addView(eyebrow);Ui.gap(hero,14);
        TextView heading=Ui.text(this,"What are we\nbuilding today?",32,Ui.TEXT);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);hero.addView(heading);Ui.gap(hero,12);
        hero.addView(Ui.text(this,"Bring an idea or connect a project.\nTake it from a first thought to working code.",14,Ui.MUTED));Ui.gap(hero,24);
        hero.addView(Ui.button(this,"↗  Build something new",false,v->input.setText("Help me build ")));Ui.gap(hero,10);
        hero.addView(Ui.button(this,"≡  Explore my project",false,v->showFiles(true)));messages.addView(hero);
    }
    private void choosePermissions(){
        String current=getSharedPreferences("agent",0).getString("mode","workspaceWrite");String[] modes={"readOnly","workspaceWrite","dangerFullAccess"};String[] labels={"Read only · inspect app workspace","Project access · edit app workspace","Full access · all app permissions"};int selected=1;for(int i=0;i<modes.length;i++)if(modes[i].equals(current))selected=i;final int[] choice={selected};
        new AlertDialog.Builder(this).setTitle("Codex permissions").setSingleChoiceItems(labels,selected,(d,w)->choice[0]=w)
            .setNegativeButton("Cancel",null).setPositiveButton("Use mode",(d,w)->{
                Runnable apply=()->{getSharedPreferences("agent",0).edit().putString("mode",modes[choice[0]]).apply();session.newChat();messages.removeAllViews();replies.clear();showWelcome();update();Toast.makeText(this,"Permission changed · new chat",Toast.LENGTH_SHORT).show();};
                if(choice[0]==2)new AlertDialog.Builder(this).setTitle("Enable full access?").setMessage("Codex commands can change or delete any files this app can reach. Android’s sandbox still applies.").setNegativeButton("Cancel",null).setPositiveButton("Enable full access",(a,b)->apply.run()).show();else apply.run();
            }).show();
    }
    private void update(){
        login.setVisibility(authenticated?View.GONE:View.VISIBLE);login.setEnabled(ready&&!preview&&loginId==null);
        logout.setVisibility(authenticated&&!preview?View.VISIBLE:View.GONE);logout.setEnabled(!busy);
        fresh.setVisibility(authenticated?View.VISIBLE:View.GONE);fresh.setEnabled(!busy);
        cancel.setVisibility(loginId==null?View.GONE:View.VISIBLE);
        input.setEnabled(!busy);send.setEnabled(ready&&authenticated&&!busy&&!preview);send.setAlpha(send.isEnabled()?1f:.45f);
        permissions.setEnabled(!busy);
        String mode=getSharedPreferences("agent",0).getString("mode","workspaceWrite");permissions.setText(("dangerFullAccess".equals(mode)?"Full access":"readOnly".equals(mode)?"Read only":"Project access")+" ▾");
    }
    private void account(JSONObject data){ready=true;authenticated=data.optJSONObject("account")!=null;status.setText(authenticated?"●  Connected · ready":"●  Sign in to start chatting");status.setTextColor(authenticated?Ui.GREEN:Ui.MUTED);update();}
    private void startLogin(){login.setEnabled(false);observe(session.login(),result->{loginId=result.optString("loginId",null);try{Uri uri=Uri.parse(result.getString("authUrl"));if(!"https".equals(uri.getScheme()))throw new IllegalStateException("Invalid sign-in URL");startActivity(new Intent(Intent.ACTION_VIEW,uri));status.setText("Finish sign-in in your browser");}catch(Exception e){error(e);}update();});}
    private void submit(){String text=input.getText().toString().trim();if((text.isEmpty()&&attached==null)||busy||!authenticated||preview)return;busy=true;input.setText("");if(welcome){messages.removeAllViews();welcome=false;}String prompt=attached==null?text:text+"\n\n"+attached;add("You\n"+text+(attached==null?"":"\n[File attached]"));attached=null;attachment.setVisibility(View.GONE);status.setText("●  Codex is working…");update();observe(session.send(prompt),r->{});}
    private TextView add(String text){
        if(welcome){messages.removeAllViews();welcome=false;}
        int newline=text.indexOf('\n');String role=newline<0?"Codex":text.substring(0,newline);String body=newline<0?text:text.substring(newline+1);
        LinearLayout card=Ui.column(this);Ui.pad(card,16);if("You".equals(role))card.setBackground(Ui.surface(Ui.PANEL,Ui.dp(this,16)));
        TextView kicker=Ui.text(this,role.toUpperCase(java.util.Locale.ROOT),10,"You".equals(role)?Ui.MUTED:Ui.GREEN);kicker.setLetterSpacing(.12f);card.addView(kicker);Ui.gap(card,10);
        TextView content=Ui.text(this,body,15,Ui.TEXT);content.setLineSpacing(Ui.dp(this,4),1);content.setTextIsSelectable(true);card.addView(content);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=Ui.dp(this,12);messages.addView(card,p);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
        // Stream handlers prepend the role; render it through a watcher below.
        content.setTag(role);return content;
    }
    private void event(String method, JSONObject data) {
        switch (method) {
            case "account/login/completed":
                loginId = null;
                if (!data.optBoolean("success")) {
                    error(new IllegalStateException(data.optString("error", "Sign in cancelled")));
                } else observe(session.account(), this::account);
                update();
                break;
            case "account/updated": observe(session.account(), this::account); break;
            case "item/agentMessage/delta": {
                String id = data.optString("itemId");
                TextView reply = replies.get(id);
                if (reply == null) { reply = add("Codex\n"); replies.put(id, reply); }
                reply.append(data.optString("delta"));
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                break;
            }
            case "item/completed": {
                JSONObject item = data.optJSONObject("item");
                if (item != null && "agentMessage".equals(item.optString("type"))) {
                    String id = item.optString("id");
                    TextView reply = replies.get(id);
                    if (reply == null) { reply = add(""); replies.put(id, reply); }
                    reply.setText(item.optString("text"));
                }
                break;
            }
            case "turn/completed": {
                busy = false;
                JSONObject turn = data.optJSONObject("turn");
                JSONObject failure = turn == null ? null : turn.optJSONObject("error");
                status.setText(failure == null ? "Ready" : failure.optString("message", "Turn failed"));
                update();
                break;
            }
            case "error": {
                JSONObject failure = data.optJSONObject("error");
                status.setText(failure == null ? "Codex reported an error" : failure.optString("message"));
                break;
            }
            case "disconnected": ready = false; error(new IllegalStateException(data.optString("message"))); break;
        }
    }
    private void error(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        busy = false;
        status.setText(error.getMessage() == null ? "Something went wrong" : error.getMessage());
        update();
    }
    private void observe(CompletableFuture<JSONObject> future, Consumer<JSONObject> success) {
        future.whenComplete((value, failure) -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (failure != null) error(failure); else success.accept(value);
        }));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==ProjectFiles.PICK&&result==RESULT_OK)files.selected(data);}
    @Override protected void onResume(){super.onResume();if(files!=null)files.resumed();}
    @Override protected void onDestroy() {
        session.observer = null;
        if(files!=null)files.close();
        super.onDestroy();
    }
}
