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

    @Override
    public void onScreenCall(Call.Details details) {
        String number = null;
        Uri handle = details.getHandle();
        if (handle != null) number = handle.getSchemeSpecificPart();
        if (number == null || number.isEmpty())
            number = details.getCallerDisplayName();

        SpamEngine engine = new SpamEngine(this);
        SpamEngine.Verdict v = engine.check(number);

        getSharedPreferences("diller", MODE_PRIVATE).edit()
                .putString("last_number", number == null ? "" : number)
                .putString("last_label", v.label)
                .putString("last_category", v.category)
                .putInt("last_score", v.score)
                .putLong("last_time", System.currentTimeMillis())
                .apply();

        CallScreeningService.CallResponse.Builder b =
                new CallScreeningService.CallResponse.Builder();

        if (v.block) {
            b.setDisallowCall(true).setRejectCall(true)
                    .setSkipCallLog(false).setSkipNotification(true);
        } else {
            b.setDisallowCall(false).setRejectCall(false)
                    .setSkipCallLog(false).setSkipNotification(false);
        }
        respondToCall(details, b.build());

        if (!v.block && v.score >= 60) {
            warn(number, v);
        }
    }

    private void warn(String number, SpamEngine.Verdict v) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CH, "Call warnings", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Warns about suspected scam and spam calls");
                nm.createNotificationChannel(ch);
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
