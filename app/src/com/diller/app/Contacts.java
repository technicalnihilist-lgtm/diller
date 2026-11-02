package com.diller.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.util.concurrent.ConcurrentHashMap;

/** Tiny helper for reverse number lookup. */
public class Contacts {

    /** PhoneLookup is a content-provider round trip (disk, and possibly a sync
     *  adapter), and Diller calls it on the incoming-call path — before the in-call
     *  screen paints and before the AI-agent decision. Memoise by number so a
     *  repeat caller needs no provider query at all. Entries are bounded and
     *  names change rarely, so a stale entry costs little. */
    private static final ConcurrentHashMap<String, String> CACHE =
            new ConcurrentHashMap<String, String>();
    private static final int CACHE_MAX = 256;

    /** Canonical digits-only key, so "+91 98..." and "098..." share one entry. */
    private static String keyOf(String number) {
        return SpamEngine.normalize(number);
    }

    public static String nameFor(Context c, String number) {
        if (number == null || number.isEmpty()) return null;
        String key = keyOf(number);
        if (key.isEmpty()) return null;

        String hit = CACHE.get(key);
        if (hit != null) {
            // "" is the cached "queried, no contact matched" answer.
            return hit.isEmpty() ? null : hit;
        }

        String name = query(c, number);
        if (CACHE.size() >= CACHE_MAX) CACHE.clear();
        CACHE.put(key, name == null ? "" : name);
        return name;
    }

    private static String query(Context c, String number) {
        Cursor cur = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            cur = c.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }
}
