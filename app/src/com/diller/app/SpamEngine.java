package com.diller.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

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

    public SpamEngine(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("diller", Context.MODE_PRIVATE);
    }

    public Set<String> blocklist() {
        Set<String> s = prefs.getStringSet("blocklist", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }
    public void block(String n) {
        Set<String> s = blocklist(); s.add(normalize(n));
        prefs.edit().putStringSet("blocklist", s).apply();
    }
    public void unblock(String n) {
        Set<String> s = blocklist(); s.remove(normalize(n));
        prefs.edit().putStringSet("blocklist", s).apply();
    }
    public boolean isBlocked(String n) { return blocklist().contains(normalize(n)); }

    public static String normalize(String n) {
        if (n == null) return "";
        return n.replaceAll("[^0-9+]", "");
    }

    private static final String[] SCAM_PREFIXES = {
            "+92", "+234", "+255", "+263", "+371", "+375", "+381", "+216", "+233"
    };

    public Verdict check(String raw) {
        String n = normalize(raw);
        String digits = n.replaceAll("[^0-9]", "");

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
        if (digits.length() >= 6 && digits.matches("(\\d)\\1+"))
            return new Verdict(CAT_SPAM, 78, "Repeated-digit spam pattern", false);

        // Simple ascending/descending run
        if (digits.length() >= 6 && (isSequential(digits, 1) || isSequential(digits, -1)))
            return new Verdict(CAT_SPAM, 66, "Sequential-number spam pattern", false);

        if (digits.length() < 6 || digits.length() > 15)
            return new Verdict(CAT_UNKNOWN, 45, "Unusual number length", false);

        return new Verdict(CAT_SAFE, 5, "Not flagged", false);
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
