package com.diller.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts Theft Guard after a reboot if the user had it switched on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        final Context app = c.getApplicationContext();
        // onReceive runs on the main thread, and BOOT_COMPLETED lands while the system
        // is still bringing everything else up. getSharedPreferences parses the whole
        // "diller" file on the calling thread the first time it runs in a process — the
        // blocklist plus up to 80 stored AI-history entries — and the read below was the
        // first thing to touch it, so that parse sat on the boot-time main thread in
        // front of whatever the launcher was drawing. goAsync() hands the same check to a
        // short-lived worker and finishes the broadcast when it is done; same flag, same
        // service start, nothing visible changes.
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    boolean on = app.getSharedPreferences("diller", Context.MODE_PRIVATE)
                            .getBoolean("guard_enabled", false);
                    if (on) TheftGuardService.start(app);
                } catch (Exception ignored) {
                    // A failed read must not crash the receiver at boot; the guard then
                    // simply stays off until the user opens Diller.
                } finally {
                    pending.finish();
                }
            }
        }, "diller-boot").start();
    }
}
