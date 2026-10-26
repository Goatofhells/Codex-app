package dev.codex.android;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** User-visible protection only while the user is completing browser sign-in. */
public final class LoginKeepAliveService extends Service {
    static void begin(Context context){context.startForegroundService(new Intent(context,LoginKeepAliveService.class));}
    static void end(Context context){context.stopService(new Intent(context,LoginKeepAliveService.class));}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("codex_login","Codex sign-in",NotificationManager.IMPORTANCE_LOW));
        Intent returnToApp=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open=PendingIntent.getActivity(this,0,returnToApp,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(1455,new Notification.Builder(this,"codex_login")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Finishing Codex sign-in")
            .setContentText("Keeping your sign-in session alive while you use the browser.")
            .setContentIntent(open).setOngoing(true).build());
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onTimeout(int startId,int type){((CodexSession)getApplication()).cancelActiveLogin();stopSelf();}
}
