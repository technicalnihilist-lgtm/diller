package com.diller.app;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Full-screen liquid-glass call screen — shown for incoming and outgoing calls. */
public class InCallActivity extends Activity {

    private static InCallActivity open;

    /** One shared worker for the caller-name lookup. bindNumber() used to start a
     *  brand-new OS thread for every call screen — and again on every onNewIntent —
     *  paying thread creation and teardown on the most latency-sensitive path in the
     *  app. A single long-lived daemon worker does the same job with no per-call cost. */
    private static ExecutorService lookupExecutor;

    private static synchronized ExecutorService lookupExecutor() {
        if (lookupExecutor == null) {
            lookupExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "diller-incall-lookup");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return lookupExecutor;
    }

    private TextView name, number, status, spam, avatar;
    private ImageView answer, decline, hangup, mute, speaker;
    private LinearLayout actions, utility;

    private Call.Callback cb;
    /** The Call that {@link #cb} is actually registered on. Telecom hands out a new
     *  Call object per call, so the callback must be tracked against the one it was
     *  attached to — unregistering from whatever is current at teardown used to leave
     *  the old call still holding a callback (and this whole view tree) forever. */
    private Call cbCall;
    private final Handler handler = new Handler();
    private long callStart = 0L;
    private boolean timerOn = false;

    private int nameToken = 0;
    private boolean muted = false;
    private boolean speakerOn = false;
    private boolean volumeBoosted = false;

    public static void finishIfOpen() {
        if (open != null) {
            try { open.finish(); } catch (Exception ignored) { }
            open = null;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        open = this;
        setContentView(R.layout.activity_incall);

        avatar = findViewById(R.id.incall_avatar);
        name = findViewById(R.id.incall_name);
        number = findViewById(R.id.incall_number);
        status = findViewById(R.id.incall_status);
        spam = findViewById(R.id.incall_spam);
        actions = findViewById(R.id.incall_actions);
        utility = findViewById(R.id.incall_utility);
        answer = findViewById(R.id.incall_answer);
        decline = findViewById(R.id.incall_decline);
        hangup = findViewById(R.id.incall_hangup);
        mute = findViewById(R.id.incall_mute);
        speaker = findViewById(R.id.incall_speaker);

        String num = getIntent().getStringExtra("number");
        if (num == null) num = "";
        bindNumber(num);

        answer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                Call c = DillerInCallService.current;
                if (c != null) {
                    try { c.answer(VideoProfile.STATE_AUDIO_ONLY); } catch (Exception ignored) { }
                }
                refresh();
            }
        });
        decline.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { endCall(true); }
        });
        hangup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { endCall(false); }
        });
        mute.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                muted = !muted;
                boolean done = false;
                if (DillerInCallService.instance != null) {
                    try { DillerInCallService.instance.setMuted(muted); done = true; }
                    catch (Exception ignored) { }
                }
                if (!done) {
                    try {
                        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) am.setMicrophoneMute(muted);
                    } catch (Exception ignored) { }
                }
                mute.setColorFilter(muted ? 0xFF0A84FF : 0xFFFFFFFF);
            }
        });
        speaker.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                speakerOn = !speakerOn;
                boolean routed = false;
                if (DillerInCallService.instance != null) {
                    try {
                        DillerInCallService.instance.setAudioRoute(speakerOn
                                ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
                        routed = true;
                    } catch (Exception ignored) { }
                }
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    if (!routed) {
                        try { am.setSpeakerphoneOn(speakerOn); } catch (Exception ignored) { }
                    }
                    if (speakerOn) boostCallVolume(am);
                }
                speaker.setColorFilter(speakerOn ? 0xFF0A84FF : 0xFFFFFFFF);
            }
        });

        attachCallback();
        refresh();
    }

    /** Keeps the state callback attached to the call currently on screen. Called from
     *  onCreate and again from onNewIntent, because a second call reuses this
     *  singleTask screen while the previous Call object is a different instance. */
    private void attachCallback() {
        Call c = DillerInCallService.current;
        if (c == cbCall) return;
        if (cb != null && cbCall != null) {
            try { cbCall.unregisterCallback(cb); } catch (Exception ignored) { }
        }
        cbCall = c;
        if (c == null) return;
        if (cb == null) {
            cb = new Call.Callback() {
                @Override public void onStateChanged(Call call, int state) { refresh(); }
            };
        }
        try { c.registerCallback(cb); } catch (Exception ignored) { cbCall = null; }
    }

    private void bindNumber(String num) {
        final String n = num == null ? "" : num;
        String display = n.isEmpty() ? "Unknown" : n;
        name.setText(display);
        number.setText(n.isEmpty() ? "No caller ID" : n);
        avatar.setText(initial(display));

        // PhoneLookup goes out to the contacts provider (disk, possibly a sync adapter),
        // and scoring the caller reads the blocklist out of prefs on a cold process.
        // Both run on a worker so the in-call screen paints immediately instead of
        // blocking onCreate on a provider round-trip.
        //
        // They are done as ONE task. As two, every call screen — and every onNewIntent
        // rebind — queued two entries on this worker and posted two hops back to the UI
        // thread, and the resolved name and the spam verdict could land in different
        // frames. They read the same number and share no state, so one task and one post
        // give the same screen with half the queueing and half the UI-thread wake-ups on
        // the most latency-sensitive path in the app.
        //
        // The worker runs on a process-wide executor that outlives this screen, so it
        // holds the application context rather than the Activity. A call declined or
        // hung up within the first few hundred milliseconds used to keep this whole
        // Activity — its inflated view tree included — reachable for as long as the
        // PhoneLookup round trip took, because the Runnable captured InCallActivity.this
        // and only the UI hop that follows needs it.
        final Context app = getApplicationContext();
        final int token = ++nameToken;
        // Hide the chip now (a rebind to a new number must not keep showing the previous
        // caller's warning) and let the worker put it back when it has a verdict.
        spam.setVisibility(View.GONE);
        // No caller ID: there is no name to resolve, and an empty number always scores
        // below the chip threshold, so the round trip would only ever paint nothing.
        if (n.isEmpty()) return;
        lookupExecutor().execute(new Runnable() {
            public void run() {
                String contact = null;
                SpamEngine.Verdict v = null;
                // Guarded separately: a throw in one lookup must not cost the other its
                // result on a screen that is already up for this call.
                try { contact = Contacts.nameFor(app, n); } catch (Exception ignored) { }
                try { v = new SpamEngine(app).check(n); } catch (Throwable ignored) { }
                final String found = contact;
                final SpamEngine.Verdict verdict = v;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (token != nameToken || isFinishing() || isDestroyed()) return;
                        if (found != null) {
                            name.setText(found);
                            avatar.setText(initial(found));
                        }
                        if (verdict != null && verdict.score >= 55) {
                            spam.setText("Possible scam — " + verdict.label
                                    + " (" + verdict.score + "/100)");
                            spam.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String num = intent.getStringExtra("number");
        if (num != null) bindNumber(num);
        attachCallback();
        refresh();
    }

    private void boostCallVolume(AudioManager am) {
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0);
        } catch (Exception ignored) { }
    }

    private void endCall(boolean reject) {
        Call c = DillerInCallService.current;
        if (c != null) {
            try {
                if (reject && c.getState() == Call.STATE_RINGING) c.reject(false, null);
                else c.disconnect();
            } catch (Exception ignored) { }
        }
        finish();
    }

    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        Call c = DillerInCallService.current;
        if (c == null) {
            status.setText("Calling…");
            hangup.setVisibility(View.VISIBLE);
            actions.setVisibility(View.GONE);
            utility.setVisibility(View.GONE);
            return;
        }
        int state = c.getState();
        switch (state) {
            case Call.STATE_RINGING:
                status.setText("Incoming call");
                actions.setVisibility(View.VISIBLE);
                hangup.setVisibility(View.GONE);
                utility.setVisibility(View.GONE);
                stopTimer();
                break;
            case Call.STATE_DIALING:
                status.setText("Calling…");
                showActiveLayout();
                stopTimer();
                break;
            case Call.STATE_CONNECTING:
                status.setText("Connecting…");
                showActiveLayout();
                stopTimer();
                break;
            case Call.STATE_ACTIVE:
                showActiveLayout();
                startTimer();
                if (!volumeBoosted) {
                    volumeBoosted = true;
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) boostCallVolume(am);
                }
                break;
            case Call.STATE_HOLDING:
                status.setText("On hold");
                showActiveLayout();
                stopTimer();
                break;
            case Call.STATE_DISCONNECTED:
            case Call.STATE_DISCONNECTING:
                finish();
                break;
            default:
                showActiveLayout();
                break;
        }
    }

    private void showActiveLayout() {
        actions.setVisibility(View.GONE);
        hangup.setVisibility(View.VISIBLE);
        utility.setVisibility(View.VISIBLE);
    }

    private void startTimer() {
        if (timerOn) return;
        if (callStart == 0L) callStart = System.currentTimeMillis();
        timerOn = true;
        if (screenVisible) handler.post(tick);
    }

    private void stopTimer() {
        timerOn = false;
        handler.removeCallbacks(tick);
        callStart = 0L;
    }

    /** True while the call screen is in front. A call outlives the screen — the user
     *  presses Home, or the proximity sensor blanks it mid-call — and the ticker used to
     *  keep waking the UI thread once a second for the whole call just to rewrite a
     *  TextView nobody could see. It now only runs while the screen is actually shown;
     *  callStart is a wall-clock stamp, so the duration is still correct on return. */
    private boolean screenVisible = false;

    @Override
    protected void onStart() {
        super.onStart();
        screenVisible = true;
        if (timerOn) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    @Override
    protected void onStop() {
        screenVisible = false;
        handler.removeCallbacks(tick);
        super.onStop();
    }

    /** Reused by the once-a-second timer; the text is always "m:ss". */
    private final StringBuilder timeBuf = new StringBuilder(8);

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!timerOn || !screenVisible) return;
            long s = (System.currentTimeMillis() - callStart) / 1000L;
            // Hand-rolled "m:ss" on a reused buffer. String.format built a Formatter,
            // a StringBuilder and boxed two ints every second of every call — steady
            // garbage on the UI thread behind the animated call screen.
            long mins = s / 60, secs = s % 60;
            timeBuf.setLength(0);
            timeBuf.append(mins).append(':');
            if (secs < 10) timeBuf.append('0');
            timeBuf.append(secs);
            status.setText(timeBuf.toString());
            handler.postDelayed(this, 1000);
        }
    };

    private static String initial(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.substring(0, 1).toUpperCase();
    }

    @Override
    protected void onDestroy() {
        if (open == this) open = null;
        handler.removeCallbacks(tick);
        // Detach from the telecom Call. The Call object outlives this Activity
        // (it lives in DillerInCallService until the call ends), so a registered
        // callback would keep the whole view tree of this screen alive.
        if (cb != null && cbCall != null) {
            try { cbCall.unregisterCallback(cb); } catch (Exception ignored) { }
        }
        cbCall = null;
        cb = null;
        super.onDestroy();
    }
}
