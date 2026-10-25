package com.diller.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Anti-theft watchdog. Runs as a foreground service, watches for SIM swaps,
 * and can sound a loud alarm + raise an alert if the phone looks stolen.
 */
public class TheftGuardService extends Service {

    static final String CH = "diller_guard";
    private static final int NOTIF_ID = 4101;
    private static final int ALARM_NOTIF_ID = 4102;

    private static Ringtone alarmTone;
    private static boolean running = false;

    private BroadcastReceiver simReceiver;

    public static void start(Context c) {
        Intent i = new Intent(c, TheftGuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, TheftGuardService.class));
        stopAlarm();
    }

    public static boolean isRunning() { return running; }

    /** Loud alarm + alert. Safe to call from any context (device admin, boot, UI). */
    public static void triggerAlarm(Context ctx, String reason) {
        try {
            ctx.getSharedPreferences("diller", Context.MODE_PRIVATE)
                    .edit().putBoolean("alarm_active", true)
                    .putString("alarm_reason", reason).apply();

            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }

            if (alarmTone == null) {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                alarmTone = RingtoneManager.getRingtone(ctx.getApplicationContext(), u);
                if (alarmTone != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    alarmTone.setLooping(true);
            }
            if (alarmTone != null && !alarmTone.isPlaying()) alarmTone.play();

            Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) {
                long[] pat = {0, 700, 300, 700, 300, 700};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    vib.vibrate(VibrationEffect.createWaveform(pat, 0));
                else vib.vibrate(pat, 0);
            }

            alertNotification(ctx, reason);
        } catch (Exception ignored) { }
    }

    public static void stopAlarm() {
        try {
            if (alarmTone != null && alarmTone.isPlaying()) alarmTone.stop();
        } catch (Exception ignored) { }
    }

    private static void alertNotification(Context ctx, String reason) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "Theft alerts", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, flags);

        Notification.Builder nb = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CH) : new Notification.Builder(ctx);
        nb.setSmallIcon(R.drawable.ic_lock)
                .setContentTitle("Theft Guard alarm")
                .setContentText(reason)
                .setStyle(new Notification.BigTextStyle().bigText(
                        reason + ". Your device may have been tampered with. "
                        + "Open Diller to lock it."))
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH);
        nm.notify(ALARM_NOTIF_ID, nb.build());
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        simReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i == null) return;
                String state = i.getStringExtra("ss");
                if ("ABSENT".equals(state) || "NOT_READY".equals(state)) {
                    triggerAlarm(c, "SIM card removed");
                }
            }
        };
        registerReceiver(simReceiver, new IntentFilter("android.intent.action.SIM_STATE_CHANGED"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildOngoing();
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else
                startForeground(NOTIF_ID, n);
        } catch (Exception ignored) { }

        if (intent != null && "alarm".equals(intent.getAction())) {
            triggerAlarm(this, intent.getStringExtra("reason") == null
                    ? "Manual alarm" : intent.getStringExtra("reason"));
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CH, getString(R.string.notif_channel_guard), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Ongoing anti-theft monitoring");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildOngoing() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, flags);

        Notification.Builder nb = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        nb.setSmallIcon(R.drawable.ic_tab_shield)
                .setContentTitle(getString(R.string.guard_title))
                .setContentText(getString(R.string.guard_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);
        return nb.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (simReceiver != null) {
            try { unregisterReceiver(simReceiver); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
