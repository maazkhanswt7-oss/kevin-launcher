package com.slay.tvlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Keeps the phone-remote web server alive while you watch other apps. */
public class RemoteService extends Service {
    private RemoteServer server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(7, notification());
        if (server == null) {
            server = new RemoteServer(getApplicationContext());
            server.start();
        }
        return START_STICKY;
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("kevin_remote", "Kevin phone remote",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            b = new Notification.Builder(this, "kevin_remote");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Kevin phone remote is on")
                .setContentText("Open the address shown on your TV from your phone")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
