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

/** Full-screen liquid-glass call screen — shown for incoming and outgoing calls. */
public class InCallActivity extends Activity {

    private static InCallActivity open;

    private TextView name, number, status, spam, avatar;
    private ImageView answer, decline, hangup, mute, speaker;
    private LinearLayout actions, utility;

    private Call.Callback cb;
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

        Call c = DillerInCallService.current;
        if (c != null) {
            cb = new Call.Callback() {
                @Override public void onStateChanged(Call call, int state) { refresh(); }
            };
            try { c.registerCallback(cb); } catch (Exception ignored) { }
        }
        refresh();
    }

    private void bindNumber(String num) {
        final String n = num == null ? "" : num;
        String display = n.isEmpty() ? "Unknown" : n;
        name.setText(display);
        number.setText(n.isEmpty() ? "No caller ID" : n);
        avatar.setText(initial(display));

        // PhoneLookup goes out to the contacts provider (disk, possibly a sync adapter).
        // Resolve the name on a worker thread so the in-call screen paints immediately
        // instead of blocking onCreate on a provider round-trip.
        final int token = ++nameToken;
        if (!n.isEmpty()) {
            new Thread(new Runnable() {
                public void run() {
                    final String contact = Contacts.nameFor(InCallActivity.this, n);
                    if (contact == null) return;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (token != nameToken || isFinishing() || isDestroyed()) return;
                            name.setText(contact);
                            avatar.setText(initial(contact));
                        }
                    });
                }
            }, "diller-incall-lookup").start();
        }

        SpamEngine.Verdict v = new SpamEngine(this).check(n);
        if (v.score >= 55) {
            spam.setVisibility(View.VISIBLE);
            spam.setText("Possible scam — " + v.label + " (" + v.score + "/100)");
        } else {
            spam.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String num = intent.getStringExtra("number");
        if (num != null) bindNumber(num);
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
        handler.post(tick);
    }

    private void stopTimer() {
        timerOn = false;
        handler.removeCallbacks(tick);
        callStart = 0L;
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!timerOn) return;
            long s = (System.currentTimeMillis() - callStart) / 1000L;
            status.setText(String.format("%d:%02d", s / 60, s % 60));
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
        Call c = DillerInCallService.current;
        if (cb != null && c != null) {
            try { c.unregisterCallback(cb); } catch (Exception ignored) { }
        }
        cb = null;
        super.onDestroy();
    }
}
