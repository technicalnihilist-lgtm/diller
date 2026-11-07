package com.diller.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** On-device scam / spam scoring. No network, no account, nothing leaves the phone. */
public class SpamEngine {

    public static final String CAT_SAFE = "Safe";
    public static final String CAT_UNKNOWN = "Unknown";
    public static final String CAT_TELEMARKETER = "Telemarketer";
    public static final String CAT_PREMIUM = "Premium rate";
    public static final String CAT_SPAM = "Spam";
    public static final String CAT_SCAM = "Scam";
    public static final String CAT_BLOCKED = "Blocked";

    public static class Verdict {
        public final String category;
        public final int score;       // 0..100 risk
        public final String label;    // short human text
        public final boolean block;   // hard block?
        Verdict(String c, int s, String l, boolean b) {
            category = c; score = s; label = l; block = b;
        }
    }

    private final SharedPreferences prefs;

    /** Verdicts are immutable, so one shared copy per number is safe and avoids
     *  re-scoring the same number on every list bind / dialer keystroke. */
    private static final Map<String, Verdict> CACHE = new ConcurrentHashMap<String, Verdict>();
    private static final int CACHE_MAX = 512;
    /** Shared blocklist snapshot; dropped whenever the blocklist changes. */
    private static volatile Set<String> blocklistCache;

    public SpamEngine(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("diller", Context.MODE_PRIVATE);
    }

    /** Shared, read-only snapshot — avoids a SharedPreferences read + set copy per lookup. */
    private Set<String> snapshot() {
        Set<String> cached = blocklistCache;
        if (cached != null) return cached;
        Set<String> s = prefs.getStringSet("blocklist", null);
        Set<String> copy = s == null ? new HashSet<String>() : new HashSet<String>(s);
        blocklistCache = copy;
        return copy;
    }

    public Set<String> blocklist() {
        return new HashSet<String>(snapshot());
    }
    public void block(String n) {
        Set<String> s = new HashSet<String>(snapshot()); s.add(normalize(n));
        prefs.edit().putStringSet("blocklist", s).apply();
        invalidate(s);
    }
    public void unblock(String n) {
        Set<String> s = new HashSet<String>(snapshot()); s.remove(normalize(n));
        prefs.edit().putStringSet("blocklist", s).apply();
        invalidate(s);
    }
    public boolean isBlocked(String n) { return snapshot().contains(normalize(n)); }

    /** Count only. Callers that just print "N numbers blocked" used to call
     *  blocklist(), which copied the whole set on every shield refresh / resume. */
    public int blocklistSize() { return snapshot().size(); }

    private static void invalidate(Set<String> fresh) {
        blocklistCache = fresh;
        CACHE.clear();
    }

    /** Strip everything but digits and a leading '+', without compiling a regex per call. */
    public static String normalize(String n) {
        if (n == null || n.isEmpty()) return "";
        // Fast path: the call log, the dialer and the screening service almost always
        // hand us a number that is already digits-only (optionally with a leading '+').
        // Returning the input as-is skips the StringBuilder plus the second String that
        // the copy below allocates. normalize() runs for every number in the recents
        // list (250 per load), on every keypad keystroke and on every spam lookup.
        boolean clean = true;
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if ((ch >= '0' && ch <= '9') || (ch == '+' && i == 0)) continue;
            clean = false;
            break;
        }
        if (clean) return n;
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if ((ch >= '0' && ch <= '9') || (ch == '+' && sb.length() == 0)) sb.append(ch);
        }
        return sb.toString();
    }

    private static final String[] SCAM_PREFIXES = {
            "+92", "+234", "+255", "+263", "+371", "+375", "+381", "+216", "+233"
    };

    public Verdict check(String raw) {
        String n = normalize(raw);
        Verdict hit = CACHE.get(n);
        if (hit != null) return hit;
        Verdict v = score(n);
        if (CACHE.size() >= CACHE_MAX) CACHE.clear();
        CACHE.put(n, v);
        return v;
    }

    private Verdict score(String n) {
        String digits = digitsOf(n);

        if (n.isEmpty() || digits.isEmpty())
            return new Verdict(CAT_UNKNOWN, 35, "Private / no caller ID", false);

        if (isBlocked(n))
            return new Verdict(CAT_BLOCKED, 100, "Blocked by you", true);

        // International prefixes heavily used for wangiri / callback scams
        for (String p : SCAM_PREFIXES) {
            if (n.startsWith(p))
                return new Verdict(CAT_SCAM, 82, "High-risk international prefix", false);
        }

        // Indian telemarketing / promotional series
        if (digits.startsWith("140") || digits.startsWith("1800"))
            return new Verdict(CAT_TELEMARKETER, 58, "Telemarketer / promo line", false);

        // Premium-rate numbers
        if (digits.startsWith("0900") || digits.startsWith("1900"))
            return new Verdict(CAT_PREMIUM, 72, "Premium-rate number", false);

        // All-same-digit or near-repeated strings (e.g. 999999, 55555)
        if (digits.length() >= 6 && isRepeated(digits))
            return new Verdict(CAT_SPAM, 78, "Repeated-digit spam pattern", false);

        // Simple ascending/descending run
        if (digits.length() >= 6 && (isSequential(digits, 1) || isSequential(digits, -1)))
            return new Verdict(CAT_SPAM, 66, "Sequential-number spam pattern", false);

        if (digits.length() < 6 || digits.length() > 15)
            return new Verdict(CAT_UNKNOWN, 45, "Unusual number length", false);

        return new Verdict(CAT_SAFE, 5, "Not flagged", false);
    }

    /** Digit-only copy of n. Hand-rolled: the old replaceAll("[^0-9]", "")
     *  compiled a Pattern and ran the regex engine on every single score.
     *  score() is reached through normalize(), so the common case is already
     *  digits-only; returning the argument then skips the StringBuilder plus the
     *  second copy of the number that this allocated for every scored call-log row
     *  (250 per recents load), every keypad keystroke and every screened call. */
    private static String digitsOf(String n) {
        int i = 0;
        for (; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch < '0' || ch > '9') break;
        }
        if (i == n.length()) return n;   // nothing to strip
        StringBuilder sb = new StringBuilder(n.length());
        for (i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    /** True when every digit is the same, e.g. 999999 / 55555.
     *  Replaces digits.matches("(\\d)\\1+"), which recompiled a Pattern per call. */
    private static boolean isRepeated(String d) {
        char first = d.charAt(0);
        for (int i = 1; i < d.length(); i++) {
            if (d.charAt(i) != first) return false;
        }
        return true;
    }

    private static boolean isSequential(String d, int step) {
        for (int i = 1; i < d.length(); i++) {
            int a = d.charAt(i - 1) - '0';
            int b = d.charAt(i) - '0';
            if ((b - a + 10) % 10 != (step + 10) % 10) return false;
        }
        return true;
    }
}
