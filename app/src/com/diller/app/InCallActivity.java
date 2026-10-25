package com.diller.app;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.telecom.Call;
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

    private boolean muted = false;
    private boolean speakerOn = false;

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
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) am.setMicrophoneMute(muted);
                } catch (Exception ignored) { }
                mute.setColorFilter(muted ? 0xFF0A84FF : 0xFFFFFFFF);
            }
        });
        speaker.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                speakerOn = !speakerOn;
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) am.setSpeakerphoneOn(speakerOn);
                } catch (Exception ignored) { }
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
        if (num == null) num = "";
        String contact = Contacts.nameFor(this, num);
        String display = contact != null ? contact : (num.isEmpty() ? "Unknown" : num);
        name.setText(display);
        number.setText(num.isEmpty() ? "No caller ID" : num);
        avatar.setText(initial(display));

        SpamEngine.Verdict v = new SpamEngine(this).check(num);
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
        super.onDestroy();
    }
}
