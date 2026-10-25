package com.diller.app;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Device-admin receiver: powers "theft detection" (failed-unlock alarm, remote lock/wipe). */
public class AdminReceiver extends DeviceAdminReceiver {

    private static final int MAX_FAILS = 5;
    private static final long WINDOW_MS = 2 * 60 * 1000L;

    @Override public void onEnabled(Context c, Intent i) { toast(c, "Device protection enabled"); }
    @Override public void onDisabled(Context c, Intent i) { toast(c, "Device protection disabled"); }

    @Override
    public void onPasswordFailed(Context c, Intent i) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences p =
                c.getSharedPreferences("diller", Context.MODE_PRIVATE);
        long first = p.getLong("fail_first", 0L);
        int count = p.getInt("fail_count", 0);
        if (first == 0L || now - first > WINDOW_MS) { first = now; count = 0; }
        count++;
        p.edit().putLong("fail_first", first).putInt("fail_count", count).apply();
        if (count >= MAX_FAILS) {
            TheftGuardService.triggerAlarm(c, "Multiple failed unlock attempts");
        }
    }

    @Override
    public void onPasswordSucceeded(Context c, Intent i) {
        c.getSharedPreferences("diller", Context.MODE_PRIVATE)
                .edit().putInt("fail_count", 0).putLong("fail_first", 0L).apply();
    }

    private void toast(Context c, String m) {
        Toast.makeText(c, m, Toast.LENGTH_SHORT).show();
    }
}
