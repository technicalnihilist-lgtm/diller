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

    /** Numbers whose PhoneLookup is in flight right now. One incoming call asks for the
     *  same name twice at almost the same instant — the AI agent's decision worker
     *  (DillerInCallService.decideAiAnswer) and the call screen's own lookup worker
     *  (InCallActivity.bindNumber) both start within a few milliseconds of the ring —
     *  and while the memo is cold both miss it, so both paid a PhoneLookup round trip
     *  (disk, often a sync adapter) on the ringing path. The second caller waits on the
     *  first and reads its answer out of the memo instead. Entries are removed as soon as
     *  their lookup finishes, so this never holds more than the few numbers actually in
     *  flight, and a lookup that throws leaves the waiter to query for itself. */
    private static final ConcurrentHashMap<String, Object> PENDING =
            new ConcurrentHashMap<String, Object>();

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

        // Single flight per number (see PENDING). A caller that arrives while this one is
        // still querying blocks here instead of issuing the same provider query, then
        // takes the answer the leader memoised just below.
        Object lock = new Object();
        Object leader = PENDING.putIfAbsent(key, lock);
        if (leader != null) lock = leader;
        synchronized (lock) {
            hit = CACHE.get(key);
            if (hit != null) return hit.isEmpty() ? null : hit;
            try {
                return lookupAndMemo(c, key, number);
            } finally {
                PENDING.remove(key, lock);
            }
        }
    }

    /** The actual PhoneLookup plus memo store, run under the number's in-flight lock. */
    private static String lookupAndMemo(Context c, String key, String number) {
        observeAddressBook(c);
        String name = query(c, number);
        // Evict ONE entry at the cap instead of clearing the whole memo. What this map
        // saves is a PhoneLookup content-provider round trip (disk, often a sync
        // adapter), and nameFor() runs on the incoming-call path before the call screen
        // paints. Clearing at the cap dropped up to CACHE_MAX already-resolved callers
        // in one go, so the next call from any of them paid that round trip again — the
        // memo behaved as if it had never been filled, exactly when the user was being
        // called most. Dropping a single entry keeps every other name warm.
        if (CACHE.size() >= CACHE_MAX) {
            java.util.Iterator<String> it = CACHE.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        CACHE.put(key, name == null ? "" : name);
        return name;
    }

    /** The PhoneLookup projection never varies, so one shared array serves every lookup.
     *  A fresh String[1] used to be built per call — the same treatment CALL_COLS and
     *  CONTACT_COLS already got on the two list queries. query() runs on the incoming-call
     *  path (before the call screen paints and before the AI agent decides), on every
     *  keypad name debounce and on every in-call screen bind, i.e. exactly on the paths
     *  where an avoidable allocation is least welcome. */
    private static final String[] LOOKUP_COLS = {ContactsContract.PhoneLookup.DISPLAY_NAME};

    private static String query(Context c, String number) {
        Cursor cur = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            cur = c.getContentResolver().query(uri, LOOKUP_COLS, null, null, null);
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }
}
