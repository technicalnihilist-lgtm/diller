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

        // Reading the call is guarded: Telecom hands this callback a Call that can already
        // be gone (the caller hung up between Telecom deciding to tell us and this method
        // running), and getDetails() / getState() then throw. onCallAdded runs in Diller's
        // own process on the ringing path, so an escape here used to take the dialer down
        // with the phone still ringing — no call screen, no fallback notification, and the
        // AI agent never got its chance to answer. On any failure the screen and the
        // notification below still come up, just with an unknown caller, which is exactly
        // what a call with no handle produces anyway.
        String number = "";
        try {
            // One getDetails() for the null check and the handle, instead of three calls
            // to the same accessor per incoming call.
            Call.Details details = call.getDetails();
            if (details != null && details.getHandle() != null)
                number = details.getHandle().getSchemeSpecificPart();
        } catch (Exception ignored) { }
        if (number == null) number = "";

        boolean ringing = false;
        try {
            ringing = call.getState() == Call.STATE_RINGING;
        } catch (Exception ignored) { }

        // 0) AI agent: answer unknown / suspected-spam calls on the user's behalf.
        //    The decision needs a PhoneLookup query (a content-provider round trip),
        //    so it runs on a worker thread instead of holding up the ringing UI.
        //    The ai_agent flag itself is read there too: on a process the system
        //    started for this call, that first getBoolean loads and parses the whole
        //    prefs file (blocklist, AI history) on the thread that is about to start
        //    the call screen, so it sat between the ring and the screen appearing.
        if (ringing) decideAiAnswer(call, number);

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

    /** Reads the ai_agent flag and scores the caller on ONE worker task, so the ringing
     *  path does no disk I/O and no provider round trip. The flag read and the decision
     *  used to be two separate tasks on this single-thread queue: the first read
     *  "ai_agent" and, when it was on, submitted the second, which then had to wait for
     *  the worker to come back round the queue before it could start its PhoneLookup.
     *  Every incoming call paid that extra hand-off between the ring and the AI
     *  answering. One task does both in the order they already ran, so the decision
     *  lands earlier and the queue carries half the work. It also holds the application
     *  context instead of the service, so a decision still queued after Telecom drops
     *  the call no longer keeps this service reachable. */
    private void decideAiAnswer(final Call call, final String number) {
        final Context app = getApplicationContext();
        DECIDE.execute(new Runnable() {
            public void run() {
                try {
                    // On a process the system started for this call, this first read loads
                    // and parses the whole prefs file (blocklist, AI history), so it must
                    // not sit between the ring and the call screen appearing.
                    if (!app.getSharedPreferences("diller", Context.MODE_PRIVATE)
                            .getBoolean("ai_agent", false)) return;
                } catch (Exception e) {
                    return;
                }
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
