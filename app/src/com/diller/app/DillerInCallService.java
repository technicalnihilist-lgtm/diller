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

    /** One reused daemon worker for the AI-answer decision. A fresh OS thread was being
     *  created (and its stack reserved, then torn down) on every single ring, on the
     *  same path that already pays for a PhoneLookup round trip. */
    private static final java.util.concurrent.ExecutorService DECIDE =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "diller-ai-decide");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** One main-thread handler for the process instead of a new one per incoming call. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
        //    The ai_agent flag itself is read there too: on a process the system
        //    started for this call, that first getBoolean loads and parses the whole
        //    prefs file (blocklist, AI history) on the thread that is about to start
        //    the call screen, so it sat between the ring and the screen appearing.
        if (ringing) decideIfAiAgent(call, number);

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

    /** Reads the ai_agent flag on the worker, so the ringing path does no disk I/O. */
    private void decideIfAiAgent(final Call call, final String number) {
        DECIDE.execute(new Runnable() {
            public void run() {
                boolean on;
                try {
                    on = getSharedPreferences("diller", MODE_PRIVATE)
                            .getBoolean("ai_agent", false);
                } catch (Exception e) {
                    return;
                }
                if (on) decideAiAnswer(call, number);
            }
        });
    }

    /** Scores the caller off the main thread; only the answer itself hops back.
     *  Same decision as before, minus the blocking contact lookup on the ringing path. */
    private void decideAiAnswer(final Call call, final String number) {
        final Context app = getApplicationContext();
        DECIDE.execute(new Runnable() {
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
                MAIN.postDelayed(new Runnable() {
                    public void run() {
                        // The 1.8s grace period is long enough for the user to decline the
                        // call, or for the caller to hang up. Calling answer() on a call
                        // that is no longer ringing threw (caught, so silently) and, when
                        // the user had just rejected the call, could still connect it.
                        // Only answer a call that is still actually ringing.
                        try {
                            if (call.getState() != Call.STATE_RINGING) return;
                            call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY);
                        } catch (Exception ignored) { }
                    }
                }, 1800);
            }
        });
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
