package dev.codex.android;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** User-granted SAF tree access plus optional Android shared-storage management. */
final class ProjectFiles {
    static final int PICK = 41;
    final Activity activity;
    final android.content.SharedPreferences prefs;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Consumer<String> attach;
    final Runnable changed;
    final LinearLayout panel, list;
    final TextView location;
    Uri tree;
    final Deque<Uri> parents = new ArrayDeque<>();
    final Deque<File> fileParents = new ArrayDeque<>();
    boolean full;
    File directory;
    long generation;

    ProjectFiles(Activity activity, Consumer<String> attach, Runnable changed) {
        this.activity=activity; this.attach=attach; this.changed=changed;
        prefs=activity.getSharedPreferences("project",0);
        String saved=prefs.getString("tree",null);
        if(saved!=null) tree=Uri.parse(saved);
        panel=Ui.column(activity);
        TextView heading=Ui.text(activity,"Your project files",24,Ui.TEXT); panel.addView(heading);
        Ui.gap(panel,6);
        panel.addView(Ui.text(activity,"Choose a folder, or enable access to shared storage.",14,Ui.MUTED));
        Ui.gap(panel,18);
        panel.addView(Ui.button(activity,"Choose project folder",true,v->pick()));
        Ui.gap(panel,10);
        panel.addView(Ui.button(activity,"Full storage access",false,v->requestFull()));
        Ui.gap(panel,10);
        LinearLayout tools=new LinearLayout(activity);
        Button up=Ui.button(activity,"↑ Up",false,v->{
            if(full && !fileParents.isEmpty()) { directory=fileParents.pop(); load(); }
            else if(!full && !parents.isEmpty()) { tree=parents.pop(); load(); }
        });
        tools.addView(up,new LinearLayout.LayoutParams(0,-2,1));
        Button revoke=Ui.button(activity,"Disconnect",false,v->revoke());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1); rp.leftMargin=Ui.dp(activity,8); tools.addView(revoke,rp);
        panel.addView(tools); Ui.gap(panel,14);
        location=Ui.text(activity,"No folder connected",13,Ui.MUTED); panel.addView(location); Ui.gap(panel,12);
        ScrollView scroll=new ScrollView(activity); list=Ui.column(activity); scroll.addView(list); panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        load();
    }
    String summary() { return full ? "Shared storage" : prefs.getString("name","No project selected"); }
    void pick() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        activity.startActivityForResult(intent,PICK);
    }
    void selected(Intent data) {
        if(data==null || data.getData()==null)return;
        Uri uri=data.getData();
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            activity.getContentResolver().takePersistableUriPermission(uri,flags);
            String name=DocumentsContract.getTreeDocumentId(uri);
            Uri document=DocumentsContract.buildDocumentUriUsingTree(uri,name);
            try(Cursor cursor=activity.getContentResolver().query(document,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
                if(cursor!=null&&cursor.moveToFirst()) name=cursor.getString(0);
            }
            String previous=prefs.getString("tree",null);
            if(previous!=null&&!previous.equals(uri.toString())) release(Uri.parse(previous));
            prefs.edit().putString("tree",uri.toString()).putString("name",name).putBoolean("write",(flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0).apply();
            tree=uri; full=false; parents.clear(); fileParents.clear(); load(); changed.run();
        } catch(Exception e) { failure(e); }
    }
    void requestFull() {
        if(Build.VERSION.SDK_INT>=30) {
            if(Environment.isExternalStorageManager()) { openFull(); return; }
            new AlertDialog.Builder(activity).setTitle("Full shared-storage access")
                .setMessage("Allow this app to browse and edit shared storage. Android still protects other apps’ private data. You can revoke this in Settings.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open Android Settings",(d,w)->{
                    prefs.edit().putBoolean("waitingFull",true).apply();
                    try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+activity.getPackageName()))); }
                    catch(ActivityNotFoundException e) { activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                }).show();
        } else new AlertDialog.Builder(activity).setTitle("Choose a project folder")
            .setMessage("Use the folder picker on this Android version. Full shared-storage management is supported on Android 11 and newer.")
            .setPositiveButton("Choose folder",(d,w)->pick()).setNegativeButton("Cancel",null).show();
    }
    void resumed() {
        if(Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager() && prefs.getBoolean("waitingFull",false)) {
            prefs.edit().putBoolean("waitingFull",false).apply(); openFull();
        }
        if(full && Build.VERSION.SDK_INT>=30 && !Environment.isExternalStorageManager()) { full=false; load(); changed.run(); }
    }
    void openFull() { full=true; directory=Environment.getExternalStorageDirectory(); fileParents.clear(); load(); changed.run(); }
    void release(Uri uri) {
        try {
            for (android.content.UriPermission permission:activity.getContentResolver().getPersistedUriPermissions()) {
                if(permission.getUri().equals(uri)) {
                    int flags=(permission.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)|(permission.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0);
                    activity.getContentResolver().releasePersistableUriPermission(uri,flags);
                }
            }
        }
        catch(Exception ignored) { }
    }
    void revoke() {
        if(full) {
            new AlertDialog.Builder(activity).setTitle("Revoke full access")
                .setMessage("Turn off all-files access in Android Settings.").setPositiveButton("Open Settings",(d,w)->{
                    if(Build.VERSION.SDK_INT>=30)activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+activity.getPackageName())));
                }).setNegativeButton("Cancel",null).show(); return;
        }
        String saved=prefs.getString("tree",null); if(saved!=null) release(Uri.parse(saved));
        prefs.edit().remove("tree").remove("name").remove("write").apply(); tree=null; parents.clear(); load(); changed.run();
    }
    static final class Entry { String name; boolean directory; Uri uri; File file; long flags; }
    void load() {
        long current=++generation;
        list.removeAllViews();
        if(!full && tree==null) {
            location.setText("Your files stay on your device");
            TextView empty=Ui.text(activity,"Connect a project\n\nBrowse files, attach code to chat, and make edits you choose to save.",16,Ui.MUTED);
            Ui.pad(empty,20); empty.setBackground(Ui.surface(Ui.PANEL,Ui.dp(activity,16))); list.addView(empty); return;
        }
        location.setText(full?directory.getAbsolutePath():summary()+" · folder access");
        list.addView(Ui.text(activity,"Loading files…",14,Ui.MUTED));
        final Uri selected=tree; final File local=directory; final boolean all=full; final boolean rootFolder=parents.isEmpty();
        io.execute(()->{
            try {
                ArrayList<Entry> entries=new ArrayList<>();
                if(all) {
                    File[] files=local.listFiles(); if(files==null) throw new IOException("This folder cannot be read");
                    for(File file:files) { Entry e=new Entry(); e.name=file.getName(); e.directory=file.isDirectory(); e.file=file; entries.add(e); }
                } else {
                    String id=rootFolder?DocumentsContract.getTreeDocumentId(selected):DocumentsContract.getDocumentId(selected);
                    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(selected,id);
                    try(Cursor c=activity.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS},null,null,null)) {
                        if(c==null)throw new IOException("Folder provider is unavailable");
                        while(c.moveToNext()) { Entry e=new Entry(); e.uri=DocumentsContract.buildDocumentUriUsingTree(selected,c.getString(0)); e.name=c.getString(1); e.directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)); e.flags=c.getLong(3); entries.add(e); }
                    }
                }
                entries.sort(Comparator.<Entry,Boolean>comparing(e->!e.directory).thenComparing(e->e.name.toLowerCase(Locale.ROOT)));
                activity.runOnUiThread(()->{ if(current!=generation||activity.isDestroyed())return; list.removeAllViews(); if(entries.isEmpty())list.addView(Ui.text(activity,"This folder is empty",15,Ui.MUTED)); for(Entry e:entries)row(e); });
            } catch(Exception e) { activity.runOnUiThread(()->{ if(current==generation){list.removeAllViews(); list.addView(Ui.text(activity,"Access unavailable. Choose the folder again.",15,Ui.MUTED));} }); }
        });
    }
    void row(Entry e) {
        TextView view=Ui.text(activity,(e.directory?"▣  ":"≡  ")+e.name+(e.directory?"  ›":""),16,Ui.TEXT);
        Ui.pad(view,16); view.setBackground(Ui.surface(Ui.PANEL,Ui.dp(activity,12)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=Ui.dp(activity,8); list.addView(view,p);
        view.setOnClickListener(v->{
            if(e.directory) { if(full){fileParents.push(directory); directory=e.file;}else{parents.push(tree); tree=e.uri;} load(); }
            else read(e);
        });
    }
    void read(Entry e) {
        io.execute(()->{
            try(InputStream input=e.file!=null?new FileInputStream(e.file):activity.getContentResolver().openInputStream(e.uri)) {
                if(input==null)throw new IOException("Cannot open file");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] block=new byte[4096]; int count;
                while((count=input.read(block))!=-1) { if(bytes.size()+count>128*1024)throw new IOException("Choose a text file smaller than 128 KB"); bytes.write(block,0,count); }
                byte[] content=bytes.toByteArray(); for(byte b:content)if(b==0)throw new IOException("Binary file preview is not supported");
                String text=new String(content,StandardCharsets.UTF_8);
                activity.runOnUiThread(()->editor(e,text));
            } catch(Exception error) { activity.runOnUiThread(()->failure(error)); }
        });
    }
    void editor(Entry entry,String text) {
        if(activity.isDestroyed())return;
        EditText editor=new EditText(activity); editor.setText(text); editor.setTextSize(13); editor.setTypeface(android.graphics.Typeface.MONOSPACE); editor.setGravity(android.view.Gravity.TOP); editor.setMinLines(8); editor.setMaxLines(18);
        boolean writable=entry.file!=null?entry.file.canWrite():prefs.getBoolean("write",false)&&(entry.flags&DocumentsContract.Document.FLAG_SUPPORTS_WRITE)!=0;
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(entry.name).setView(editor)
            .setNegativeButton("Close",null).setNeutralButton("Attach to chat",(d,w)->attach.accept("File: "+entry.name+"\n```\n"+editor.getText()+"\n```"))
            .setPositiveButton(writable?"Save changes":"Read only",null).create();
        dialog.setOnShowListener(d->{ dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(writable); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String updated=editor.getText().toString();
            new AlertDialog.Builder(activity).setTitle("Save "+entry.name+"?").setMessage("This replaces the file’s contents.")
                .setNegativeButton("Cancel",null).setPositiveButton("Save",(confirm,which)->io.execute(()->{
                    try(OutputStream out=entry.file!=null?new FileOutputStream(entry.file):activity.getContentResolver().openOutputStream(entry.uri,"wt")) {
                        if(out==null)throw new IOException("Cannot write file"); out.write(updated.getBytes(StandardCharsets.UTF_8));
                        activity.runOnUiThread(()->{dialog.dismiss(); Toast.makeText(activity,"File saved",Toast.LENGTH_SHORT).show();});
                    }catch(Exception error){activity.runOnUiThread(()->failure(error));}
                })).show();
        });}); dialog.show();
    }
    void failure(Exception e) { if(!activity.isDestroyed())new AlertDialog.Builder(activity).setTitle("File access").setMessage(e.getMessage()).setPositiveButton("OK",null).show(); }
    void close() { generation++; io.shutdownNow(); }
}
