package com.diller.app;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a plain-language summary of today's calls straight from the call log. */
public class DailySummary {

    public static String build(Context c) {
        long start = startOfToday();
        Cursor cur = null;
        LinkedHashMap<String, int[]> byName = new LinkedHashMap<>();
        int total = 0, out = 0, in = 0, missed = 0;
        long secs = 0;

        try {
            cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.TYPE, CallLog.Calls.DURATION},
                    CallLog.Calls.DATE + ">=?", new String[]{String.valueOf(start)},
                    CallLog.Calls.DATE + " DESC");
            if (cur != null) {
                while (cur.moveToNext()) {
                    String num = cur.getString(0);
                    String name = cur.getString(1);
                    int type = cur.getInt(2);
                    long dur = cur.getLong(3);

                    String key = (name != null && !name.isEmpty()) ? name
                            : (num == null || num.isEmpty() ? "Unknown" : num);
                    int[] v = byName.get(key);
                    if (v == null) { v = new int[]{0, 0}; byName.put(key, v); }
                    v[0]++;
                    v[1] += (int) dur;

                    total++;
                    secs += dur;
                    if (type == CallLog.Calls.OUTGOING_TYPE) out++;
                    else if (type == CallLog.Calls.INCOMING_TYPE) in++;
                    else if (type == CallLog.Calls.MISSED_TYPE) missed++;
                }
            }
        } catch (Exception e) {
            return "Couldn't read the call log — allow the Call log permission.";
        } finally {
            if (cur != null) cur.close();
        }

        if (total == 0) return "Aaj koi call nahi hui.";

        List<Map.Entry<String, int[]>> list = new ArrayList<>(byName.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("Aaj total ").append(total).append(" calls — ")
          .append(out).append(" outgoing, ").append(in).append(" incoming, ")
          .append(missed).append(" missed.\n");
        sb.append("Talk time: ").append(secs / 60).append(" min ").append(secs % 60).append(" s.\n\n");
        sb.append("Sabse zyada baat: ");
        int n = Math.min(3, list.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, int[]> e = list.get(i);
            if (i > 0) sb.append(", ");
            sb.append(e.getKey()).append(" — ").append(e.getValue()[0]).append(" baar");
        }
        sb.append(".");
        return sb.toString();
    }

    private static long startOfToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
