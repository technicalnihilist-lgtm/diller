package com.diller.app;

import android.content.Context;
import android.database.ContentObserver;
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

    /** Set once per process, when the address-book observer is registered. */
    private static volatile boolean observing;

    /** The memo is dropped whenever the address book changes — a contact Diller itself
     *  adds or deletes, a sync, or an edit in another contacts app. Without this, a number
     *  looked up before the contact existed stayed cached as "no name" (so the keypad
     *  subtitle and the in-call screen kept showing the bare number) and a renamed or
     *  deleted contact kept its old name, until the process died. */
    private static void observeAddressBook(Context c) {
        if (observing) return;
        observing = true;
        try {
            // A null Handler delivers onChange on the caller's binder thread, so this
            // never queues work on the UI thread; CACHE is a ConcurrentHashMap.
            c.getApplicationContext().getContentResolver().registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, new ContentObserver(null) {
                        @Override public void onChange(boolean selfChange) { CACHE.clear(); }
                    });
        } catch (Exception e) {
            observing = false;   // try again on the next lookup
        }
    }

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

        observeAddressBook(c);
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
