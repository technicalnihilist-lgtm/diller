package com.diller.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.InCallService;

/** Bridges Android Telecom into Diller's own liquid-glass in-call screen. */
public class DillerInCallService extends InCallService {

    static final String CH = "diller_call";
    private static final int NOTIF_ID = 4301;

    static DillerInCallService instance;
    static Call current;

    /** The channel is process-wide and never changes, so it only needs creating once.
     *  Re-creating it on every call was a binder round-trip to the notification
     *  manager on the ringing path, before the call screen had painted. */
    private static volatile boolean channelReady = false;

    @Override
    public void onCallAdded(Call call) {
        instance = this;
        current = call;

        String number = "";
        if (call.getDetails() != null && call.getDetails().getHandle() != null)
            number = call.getDetails().getHandle().getSchemeSpecificPart();

        boolean ringing = call.getState() == Call.STATE_RINGING;

        // 0) AI agent: answer unknown / suspected-spam calls on the user's behalf.
        //    The decision needs a PhoneLookup query (a content-provider round trip),
        //    so it runs on a worker thread instead of holding up the ringing UI.
        if (ringing && getSharedPreferences("diller", MODE_PRIVATE).getBoolean("ai_agent", false)) {
            decideAiAnswer(call, number);
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

    /** Scores the caller off the main thread; only the answer itself hops back.
     *  Same decision as before, minus the blocking contact lookup on the ringing path. */
    private void decideAiAnswer(final Call call, final String number) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                boolean known;
                String label;
                int score;
                try {
                    known = Contacts.nameFor(app, number) != null;
                    SpamEngine.Verdict v = new SpamEngine(app).check(number);
                    label = v.label;
                    score = v.score;
                } catch (Exception e) {
                    return;
                }
                if (known && score < 60) return;
                AiHistory.add(app, "call", "AI agent answered",
                        (number == null || number.isEmpty() ? "Unknown" : number) + " - " + label);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        try { call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY); }
                        catch (Exception ignored) { }
                    }
                }, 1800);
            }
        }, "diller-ai-decide").start();
    }

    private void showCallNotification(String number, boolean ringing) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!channelReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CH, "Calls", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Diller call screen");
                nm.createNotificationChannel(ch);
                channelReady = true;
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
