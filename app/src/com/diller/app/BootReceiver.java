package com.diller.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts Theft Guard after a reboot if the user had it switched on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        boolean on = c.getSharedPreferences("diller", Context.MODE_PRIVATE)
                .getBoolean("guard_enabled", false);
        if (on) TheftGuardService.start(c);
    }
}
