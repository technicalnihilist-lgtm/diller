package com.diller.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.telecom.Call;
import android.telecom.InCallService;

/** Bridges Android Telecom into Diller's own liquid-glass in-call screen. */
public class DillerInCallService extends InCallService {

    static final String CH = "diller_call";
    private static final int NOTIF_ID = 4301;

    static DillerInCallService instance;
    static Call current;

    @Override
    public void onCallAdded(Call call) {
        instance = this;
        current = call;

        String number = "";
        if (call.getDetails() != null && call.getDetails().getHandle() != null)
            number = call.getDetails().getHandle().getSchemeSpecificPart();

        boolean ringing = call.getState() == Call.STATE_RINGING;

        // 0) AI agent: answer unknown / suspected-spam calls on the user's behalf.
        if (ringing && getSharedPreferences("diller", MODE_PRIVATE).getBoolean("ai_agent", false)) {
            boolean known = Contacts.nameFor(this, number) != null;
            SpamEngine.Verdict v = new SpamEngine(this).check(number);
            if (!known || v.score >= 60) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        try { call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY); }
                        catch (Exception ignored) { }
                    }
                }, 1800);
                AiHistory.add(this, "call", "AI agent answered",
                        (number == null || number.isEmpty() ? "Unknown" : number) + " - " + v.label);
            }
        }

        // 1) Try to show the activity directly (works when Diller is the default dialer).
        try {
            Intent i = new Intent(this, InCallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("number", number);
            startActivity(i);
        } catch (Exception ignored) { }

        // 2) Fallback: a full-screen-intent notification, in case background
        //    activity starts are restricted on this ROM.
        showCallNotification(number, ringing);
    }

    private void showCallNotification(String number, boolean ringing) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CH, "Calls", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Diller call screen");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, InCallActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("number", number);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

            Notification.Builder nb = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            nb.setSmallIcon(R.drawable.ic_call)
                    .setContentTitle(ringing ? "Incoming call" : "Calling")
                    .setContentText(number == null || number.isEmpty() ? "Unknown" : number)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setPriority(Notification.PRIORITY_HIGH);
            if (ringing) nb.setFullScreenIntent(pi, true);
            nm.notify(NOTIF_ID, nb.build());
        } catch (Exception ignored) { }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (call == current) current = null;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) { }
        InCallActivity.finishIfOpen();
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
