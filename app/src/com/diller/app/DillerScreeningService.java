package com.diller.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/** Screens every incoming call and flags scams/spam using the on-device engine. */
public class DillerScreeningService extends CallScreeningService {

    static final String CH = "diller_screen";

    /** Created once per process instead of on every flagged call: each
     *  createNotificationChannel is an IPC into the notification manager. */
    private static volatile boolean channelReady = false;

    /** Handed out when scoring throws, so the call is still answered through Telecom. */
    private static final SpamEngine.Verdict UNSCORED =
            new SpamEngine.Verdict(SpamEngine.CAT_UNKNOWN, 0, "Couldn't score this call", false);

    @Override
    public void onScreenCall(Call.Details details) {
        String number = null;
        SpamEngine.Verdict v;
        // Everything up to the verdict is guarded: onScreenCall is a Telecom callback
        // running in Diller's own process, so anything that escapes it crashes the
        // dialer — and this one runs while a call is ringing. Scoring is also the only
        // step here that touches disk (SpamEngine's first check reads the blocklist out
        // of SharedPreferences, which on a cold process loads and parses the file), and
        // a throw there used to take the whole process down instead of letting the call
        // through. On any failure we answer Telecom normally and allow the call.
        try {
            Uri handle = details.getHandle();
            if (handle != null) number = handle.getSchemeSpecificPart();
            if (number == null || number.isEmpty())
                number = details.getCallerDisplayName();

            SpamEngine engine = new SpamEngine(this);
            v = engine.check(number);
        } catch (Throwable t) {
            v = UNSCORED;
        }

        CallScreeningService.CallResponse.Builder b =
                new CallScreeningService.CallResponse.Builder();

        if (v.block) {
            b.setDisallowCall(true).setRejectCall(true)
                    .setSkipCallLog(false).setSkipNotification(true);
        } else {
            b.setDisallowCall(false).setRejectCall(false)
                    .setSkipCallLog(false).setSkipNotification(false);
        }
        // Reply to Telecom first: this is the time-critical step and the framework
        // gives a screening service only a few seconds to answer.
        respondToCall(details, b.build());

        // The five "last_number" / "last_label" / "last_category" / "last_score" /
        // "last_time" keys that used to be written here were never read by anything:
        // the in-call screen gets the number from its Intent and re-scores it on its
        // own worker. Nothing displayed them, yet every screened call still paid for a
        // SharedPreferences commit, and a commit serialises the whole "diller" file —
        // the blocklist plus up to 80 AI-history entries — to disk. That is a full
        // prefs rewrite per incoming call, in the background, for data no one reads.
        // Dropping it leaves every visible behaviour exactly as it was.

        if (!v.block && v.score >= 60) {
            warn(number, v);
        }
    }

    private void warn(String number, SpamEngine.Verdict v) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!channelReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CH, "Call warnings", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Warns about suspected scam and spam calls");
                nm.createNotificationChannel(ch);
                channelReady = true;
            }
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

            Notification.Builder nb = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CH)
                    : new Notification.Builder(this);
            nb.setSmallIcon(R.drawable.ic_tab_shield)
                    .setContentTitle("Possible scam call")
                    .setContentText((number == null ? "Unknown" : number) + " — " + v.label)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "Diller flagged this incoming call: " + v.label
                            + " (risk " + v.score + "/100). Answer with caution."))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            nm.notify(4201, nb.build());
        } catch (Exception ignored) { }
    }
}
