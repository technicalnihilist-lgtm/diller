package com.diller.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final int REQ_SCREEN = 11;
    private static final int REQ_DIALER = 12;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0A84FF;
    private static final int BLUE_BRIGHT = 0xFF4C9AFF;
    private static final int RED = 0xFFFF453A;
    /** Pre-built colour lists: setTextColor(int) allocates a new ColorStateList on every call. */
    private static final ColorStateList NAME_NORMAL = ColorStateList.valueOf(WHITE);
    private static final ColorStateList NAME_SPAM = ColorStateList.valueOf(RED);

    private FrameLayout content;
    private SwipeTabHost root;
    private View navCalls, navContacts, navKeypad, navShield, navAi;
    private View vCalls, vContacts, vKeypad, vShield, vAi;
    private int current = 2; // Keypad by default, like the iOS Phone app
    /** Tab pages / nav items in tab order, built once instead of per switch. */
    private View[] tabViews;
    private View[] navViews;
    /** Which nav item is currently drawn as selected; -1 before the first showTab. */
    private int styledTab = -1;

    private SpamEngine engine;

    // keypad
    private TextView dialDisplay, dialSub, dialChip;
    private View btnBack, btnAdd;
    private View dialerBanner;
    private final StringBuilder typed = new StringBuilder();

    // calls
    private ListView recentsList;
    private TextView recentsEmpty;
    private RecentsAdapter recentsAdapter;
    private boolean missedOnly = false;

    // contacts
    private ListView contactsList;
    private TextView contactsEmpty;
    private EditText search;
    private ContactsAdapter contactsAdapter;
    /** Coalesces contact-search keystrokes into one provider query. */
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchPending;
    /** Contacts queries run off the UI thread so typing and startup never stall. */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    /** Bumped per request; a result whose token is stale is dropped. Main thread only. */
    private int contactsToken = 0;
    /** Same idea for the call log, which is now queried off the UI thread too. */
    private int callsToken = 0;
    /** True while a list query is still on the I/O thread, plus the filter it was
     *  started with. showTab() and onResume() both ask for a reload, so tapping
     *  between tabs (or resuming straight after a switch) queued a second full
     *  call-log scan / address-book walk behind the first, and the token then threw
     *  its answer away. Skipping the duplicate is safe: the query already running was
     *  started from the same state and its result is what the new request would get. */
    private boolean callsLoading = false;
    private boolean callsLoadingMissed = false;
    private boolean contactsLoading = false;
    private String contactsLoadingFilter = "";
    /** Guards against a slow name lookup landing on a newer dialled number. */
    private int dialNameToken = 0;
    /** Coalesces keypad name-lookups into one provider query once typing pauses. */
    private Runnable dialPending;
    /** Number the keypad subtitle currently describes, and that subtitle's text.
     *  Lets updateDisplay() skip the clear + provider lookup when nothing changed. */
    private String dialNameNum;
    private String dialNameText = "";
    /** Same idea for the daily AI summary, which reads the call log. */
    private int summaryToken = 0;

    // shield
    private TextView screenStatus, shieldPill, blockCount, guardStatus, dialerStatus;
    private Switch guardSwitch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        engine = new SpamEngine(this);

        content = findViewById(R.id.content);
        navCalls = findViewById(R.id.tab_calls);
        navContacts = findViewById(R.id.tab_contacts);
        navKeypad = findViewById(R.id.tab_keypad);
        navShield = findViewById(R.id.nav_shield);
        navAi = findViewById(R.id.tab_ai);

        // Only the page the app actually opens on is inflated during cold start.
        // All five used to be inflated here — ~480 lines of layout parsed, plus five
        // adapters, their listeners and switches built — for pages that are hidden at
        // launch (the keypad is the default) and that the user may never open. The
        // layouts have not changed and neither has any page; they are simply created
        // by inflateTab() the first time their tab is shown.
        tabViews = new View[5];
        navViews = new View[]{navCalls, navContacts, navKeypad, navShield, navAi};
        inflateTab(2);   // keypad — the page the app opens on

        navCalls.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(0); } });
        navContacts.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(1); } });
        navKeypad.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(2); } });
        navShield.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(3); } });
        navAi.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(4); } });

        root = findViewById(R.id.root);
        if (root != null) {
            root.setListener(new SwipeTabHost.Listener() {
                public void onSwipe(int dir) {
                    int next = current + dir;
                    if (next < 0) next = 0;
                    if (next > 4) next = 4;
                    if (next != current) showTab(next, dir);
                }
            });
        }
        showTab(2);
        handleIntent(getIntent());
        requestPerms();
    }

    // ---------------- tabs ----------------

    private void showTab(int i) { showTab(i, 0); }

    /** Creates a tab page on first use. Each page is inflated and wired at most once,
     *  and every view field it owns (adapter, list, switch, banner) is set before its
     *  setup method returns, so the tab behaves exactly as it did when all five pages
     *  were built up front. */
    private View inflateTab(int i) {
        View v;
        switch (i) {
            case 0:
                v = getLayoutInflater().inflate(R.layout.view_recents, content, false);
                vCalls = v;
                content.addView(v);
                tabViews[0] = v;
                setupCalls();
                break;
            case 1:
                v = getLayoutInflater().inflate(R.layout.view_contacts, content, false);
                vContacts = v;
                content.addView(v);
                tabViews[1] = v;
                setupContacts();
                break;
            case 2:
                v = getLayoutInflater().inflate(R.layout.view_keypad, content, false);
                vKeypad = v;
                content.addView(v);
                tabViews[2] = v;
                setupKeypad();
                break;
            case 3:
                v = getLayoutInflater().inflate(R.layout.view_shield, content, false);
                vShield = v;
                content.addView(v);
                tabViews[3] = v;
                setupShield();
                break;
            case 4:
                v = getLayoutInflater().inflate(R.layout.view_ai, content, false);
                vAi = v;
                content.addView(v);
                tabViews[4] = v;
                setupAi();
                break;
            default:
                return vKeypad;
        }
        return v;
    }

    private void showTab(int i, int dir) {
        current = i;
        // Pages that have never been opened are still null here; build this one before
        // it is shown, then only the two views that actually change visibility.
        if (tabViews[i] == null) inflateTab(i);
        // The tab pages and nav items are built once; re-creating these
        // arrays on every switch allocated two arrays per tap for no reason.
        View[] views = tabViews;
        for (int k = 0; k < views.length; k++) {
            View page = views[k];
            if (page != null) page.setVisibility(k == i ? View.VISIBLE : View.GONE);
        }
        // Only the tab leaving and the tab arriving change appearance. Restyling all
        // five re-resolved the capsule drawable (a fresh Drawable per call) and walked
        // every nav child to re-apply a colour filter on each switch, mid-animation.
        if (styledTab != i) {
            if (styledTab >= 0 && styledTab < navViews.length) styleNav(navViews[styledTab], false);
            styleNav(navViews[i], true);
            styledTab = i;
        }
        if (dir != 0) {
            View in = views[i];
            float w = getResources().getDisplayMetrics().widthPixels;
            in.setTranslationX(dir * w * 0.20f);
            in.setAlpha(0.35f);
            in.animate().translationX(0f).alpha(1f).setDuration(190).start();
        }
        if (i == 0) loadCalls();
        if (i == 1) loadContacts(search == null ? "" : search.getText().toString());
        if (i == 3) refreshShield();
        if (i == 4) refreshAi();
    }

    private void styleNav(View item, boolean sel) {
        if (!(item instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) item;
        if (g.getChildCount() < 2) return;
        // child 0 = icon holder (gets the blue capsule), child 1 = label
        View holder = g.getChildAt(0);
        holder.setBackgroundResource(sel ? R.drawable.nav_tab_capsule : 0);
        tintAll(holder, sel ? WHITE : 0xFFB3B3B8);
        View lb = g.getChildAt(1);
        if (lb instanceof TextView) ((TextView) lb).setTextColor(sel ? BLUE_BRIGHT : 0xFFB3B3B8);
    }

    private void tintAll(View v, int color) {
        if (v instanceof ImageView) { ((ImageView) v).setColorFilter(color); return; }
        if (v instanceof TextView) { ((TextView) v).setTextColor(color); return; }
        if (v instanceof ViewGroup) {
            ViewGroup gg = (ViewGroup) v;
            for (int i = 0; i < gg.getChildCount(); i++) tintAll(gg.getChildAt(i), color);
        }
    }

    // ---------------- keypad ----------------

    private void setupKeypad() {
        dialDisplay = vKeypad.findViewById(R.id.dial_display);
        dialSub = vKeypad.findViewById(R.id.dial_sub);
        dialChip = vKeypad.findViewById(R.id.dial_chip);
        btnBack = vKeypad.findViewById(R.id.btn_back);
        btnAdd = vKeypad.findViewById(R.id.btn_add_contact);
        dialerBanner = vKeypad.findViewById(R.id.dialer_banner);
        vKeypad.findViewById(R.id.banner_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestDefaultDialer(); }
        });

        buildGrid((FrameLayout) vKeypad.findViewById(R.id.keypad_grid));

        vKeypad.findViewById(R.id.btn_call).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { placeCall(typed.toString()); }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (typed.length() > 0) typed.deleteCharAt(typed.length() - 1);
                updateDisplay();
            }
        });
        btnBack.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { typed.setLength(0); updateDisplay(); return true; }
        });
        btnAdd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addContact(typed.toString()); }
        });
        updateDisplay();
    }

    private void buildGrid(FrameLayout host) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        g.setRowCount(4);
        g.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        String[] subs = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};

        for (int i = 0; i < 12; i++) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.rowSpec = GridLayout.spec(i / 3, 1f);
            lp.columnSpec = GridLayout.spec(i % 3, 1f);
            lp.width = 0;
            lp.height = 0;

            FrameLayout cell = new FrameLayout(this);
            cell.setLayoutParams(lp);

            FrameLayout key = new FrameLayout(this);
            key.setLayoutParams(new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));
            key.setBackgroundResource(R.drawable.key_bg);

            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView d = new TextView(this);
            d.setText(digits[i]);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
            d.setTextColor(WHITE);
            d.setGravity(Gravity.CENTER);
            d.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            inner.addView(d);

            if (subs[i].length() > 0) {
                TextView sub = new TextView(this);
                sub.setText(subs[i]);
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                sub.setTextColor(WHITE);
                sub.setGravity(Gravity.CENTER);
                sub.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                sub.setLetterSpacing(0.2f);
                inner.addView(sub);
            }

            key.addView(inner);
            cell.addView(key);

            final String val = digits[i];
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { append(val); }
            });
            if ("0".equals(digits[i])) {
                cell.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        try { vKeypad.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); }
                        catch (Exception ignored) { }
                        append("+");
                        return true;
                    }
                });
            }
            g.addView(cell);
        }
        host.addView(g);
    }

    private void append(String s) {
        try { vKeypad.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); }
        catch (Exception ignored) { }
        typed.append(s);
        updateDisplay();
    }

    private void updateDisplay() {
        String num = typed.toString();
        dialDisplay.setText(num);

        if (num.isEmpty()) {
            dialSub.setText("");
            dialChip.setVisibility(View.GONE);
            btnBack.setVisibility(View.INVISIBLE);
            btnAdd.setVisibility(View.INVISIBLE);
            return;
        }
        btnBack.setVisibility(View.VISIBLE);
        btnAdd.setVisibility(View.VISIBLE);

        // PhoneLookup goes out to the contacts provider (disk, possibly a sync adapter),
        // so resolve the name on the I/O thread instead of blocking every keypad tap.
        // Debounced like the contacts search: typing a ten-digit number used to fire one
        // provider round trip per keystroke on the shared I/O thread, where they also
        // queued ahead of a pending call-log or contacts query.
        final String lookup = num;
        final int nameToken = ++dialNameToken;
        if (dialPending != null) { searchHandler.removeCallbacks(dialPending); dialPending = null; }
        // updateDisplay() also runs from onResume(), where the dialled number is
        // almost always the one already resolved. Blanking the subtitle and firing
        // another provider round trip made the caller name flicker on every return
        // to the app; when the number is unchanged the cached text is still correct.
        if (lookup.equals(dialNameNum)) {
            dialSub.setText(dialNameText);
        } else {
            dialSub.setText("");
            dialNameNum = null;
            dialNameText = "";
            dialPending = new Runnable() {
                public void run() {
                    dialPending = null;
                    ioExecutor.execute(new Runnable() {
                        public void run() {
                            if (nameToken != dialNameToken) return;   // superseded while queued
                            final String name = Contacts.nameFor(MainActivity.this, lookup);
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (nameToken != dialNameToken || isFinishing() || isDestroyed()) return;
                                    final String shown = name != null ? name : "";
                                    dialNameNum = lookup;
                                    dialNameText = shown;
                                    dialSub.setText(shown);
                                }
                            });
                        }
                    });
                }
            };
            searchHandler.postDelayed(dialPending, 180);
        }

        SpamEngine.Verdict v = engine.check(num);
        if (v.score >= 55) {
            dialChip.setVisibility(View.VISIBLE);
            dialChip.setText(v.category.toUpperCase());
        } else {
            dialChip.setVisibility(View.GONE);
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------------- dialing ----------------

    private void placeCall(String number) {
        if (number == null || number.trim().isEmpty()) { toast("Enter a number first"); return; }
        Uri uri = Uri.parse("tel:" + Uri.encode(number));
        boolean canCall = checkSelfPermission(Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED;

        if (isDefaultDialer() && canCall) {
            // Show Diller's own liquid-glass call screen immediately, then place the call.
            Intent ic = new Intent(this, InCallActivity.class);
            ic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ic.putExtra("number", number);
            ic.putExtra("outgoing", true);
            try { startActivity(ic); } catch (Exception ignored) { }
            try {
                TelecomManager tm = getSystemService(TelecomManager.class);
                if (tm != null) { tm.placeCall(uri, new Bundle()); return; }
            } catch (Exception ignored) { }
        }
        Intent i = new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL, uri);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("No dialer available");
            return;
        }
        if (!isDefaultDialer()) askDefaultDialer();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri data = intent.getData();
        if (data == null || !"tel".equals(data.getScheme())) return;
        String num = data.getSchemeSpecificPart();
        if (num == null || num.isEmpty()) return;

        if (Intent.ACTION_DIAL.equals(action)) {
            typed.setLength(0);
            typed.append(num);
            updateDisplay();
            showTab(2);
        } else if (Intent.ACTION_CALL.equals(action)) {
            if (isDefaultDialer()) {
                placeCall(num);
            } else {
                typed.setLength(0);
                typed.append(num);
                updateDisplay();
                showTab(2);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void askDefaultDialer() {
        new AlertDialog.Builder(this)
                .setTitle("See the Diller call screen")
                .setMessage("Make Diller your default Phone app and every call — incoming or outgoing — opens Diller's liquid-glass call screen.")
                .setPositiveButton("Set default", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { requestDefaultDialer(); }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void addContact(String number) {
        showNewContactDialog(number == null ? "" : number);
    }

    /** Create a contact inside Diller — no external contacts app involved. */
    private void showNewContactDialog(String prefillNumber) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);

        final EditText nameIn = new EditText(this);
        nameIn.setHint("Name");
        nameIn.setSingleLine(true);
        nameIn.setTextColor(WHITE);
        nameIn.setHintTextColor(0xFF8E8E93);

        final EditText numIn = new EditText(this);
        numIn.setHint("Phone number");
        numIn.setSingleLine(true);
        numIn.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        numIn.setText(prefillNumber);
        numIn.setTextColor(WHITE);
        numIn.setHintTextColor(0xFF8E8E93);

        box.addView(nameIn);
        box.addView(numIn);

        new AlertDialog.Builder(this)
                .setTitle("New contact")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        final String nm = nameIn.getText().toString().trim();
                        final String num = numIn.getText().toString().trim();
                        if (nm.isEmpty() || num.isEmpty()) { toast("Name and number required"); return; }
                        // applyBatch goes out to ContactsProvider — disk, and often a sync
                        // adapter — so it runs on the I/O thread; only the toast and the
                        // list refresh hop back. Same treatment the call delete already gets.
                        ioExecutor.execute(new Runnable() {
                            public void run() {
                                final boolean ok = saveContact(nm, num);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (isFinishing() || isDestroyed()) return;
                                        if (ok) {
                                            toast("Contact saved");
                                            if (current == 1) loadContacts(search == null ? "" : search.getText().toString());
                                            updateDisplay();
                                        } else {
                                            toast("Couldn't save — allow the Contacts permission");
                                        }
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean saveContact(String name, String number) {
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            int raw = ops.size();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, raw)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, raw)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build());
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- calls ----------------

    private void setupCalls() {
        recentsList = vCalls.findViewById(R.id.recents_list);
        recentsEmpty = vCalls.findViewById(R.id.recents_empty);
        recentsAdapter = new RecentsAdapter();
        recentsList.setAdapter(recentsAdapter);
        recentsList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                placeCall(recentsAdapter.items.get(pos).number);
            }
        });
        vCalls.findViewById(R.id.btn_edit).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toast("Tap a call to redial"); }
        });
        vCalls.findViewById(R.id.btn_filter).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                missedOnly = !missedOnly;
                toast(missedOnly ? "Showing missed calls" : "Showing all calls");
                loadCalls();
            }
        });
        // No loadCalls() here on purpose. The recents tab is hidden at launch (the
        // keypad is the default page), yet this queried the whole call log during
        // onCreate — a provider round trip plus 250 rows of row building and spam
        // scoring, on the same cold-start I/O the keypad and contacts were already
        // competing for. showTab(0) loads the list the first time the tab is shown,
        // so it looks exactly the same when seen.
    }

    /** Kicks off the call-log query on a background thread; the list is updated when it returns. */
    private void loadCalls() {
        final boolean missed = missedOnly;
        // A query for this exact filter is already in flight; its result is this
        // request's result too, so do not queue a duplicate provider scan behind it.
        if (callsLoading && callsLoadingMissed == missed) return;
        callsLoading = true;
        callsLoadingMissed = missed;
        final int token = ++callsToken;
        ioExecutor.execute(new Runnable() {
            public void run() {
                final List<CallRow> rows = queryCalls(missed);
                runOnUiThread(new Runnable() {
                    public void run() {
                        // Drop results for an older request, or a dead activity.
                        if (token != callsToken) return;
                        callsLoading = false;
                        if (isFinishing() || isDestroyed()) return;
                        recentsAdapter.set(rows);
                        recentsEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private List<CallRow> queryCalls(boolean missedOnly) {
        List<CallRow> rows = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG)
                == PackageManager.PERMISSION_GRANTED) {
            Cursor cur = null;
            try {
                // Filter missed calls in the query instead of in Java: without a selection
                // the provider handed back every call ever logged and this loop walked the
                // whole log (cursor window included) just to collect 250 missed rows.
                // The LIMIT rides along in the sort order so the provider stops after the
                // 250 newest rows instead of sorting the entire call log first.
                String sel = missedOnly ? CallLog.Calls.TYPE + "=?" : null;
                String[] selArgs = missedOnly
                        ? new String[]{String.valueOf(CallLog.Calls.MISSED_TYPE)} : null;
                cur = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                        new String[]{CallLog.Calls._ID, CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                        sel, selArgs, CallLog.Calls.DATE + " DESC LIMIT 250");
                if (cur != null) {
                    while (cur.moveToNext() && rows.size() < 250) {
                        long callId = cur.getLong(0);
                        String name = cur.getString(1);
                        String num = cur.getString(2);
                        int type = cur.getInt(3);
                        long date = cur.getLong(4);

                        CallRow r = new CallRow();
                        r.id = callId;
                        r.number = num == null ? "" : num;
                        boolean hasName = name != null && !name.isEmpty();
                        r.title = hasName ? name : (r.number.isEmpty() ? "Unknown" : r.number);
                        r.sub = hasName && !r.number.isEmpty() ? r.number : typeLabel(type);
                        r.iconRes = typeIcon(type);
                        r.time = DateUtils.getRelativeTimeSpanString(date).toString();
                        r.initial = initialOf(r.title);
                        SpamEngine.Verdict v = engine.check(r.number);
                        r.spam = v.score >= 60;
                        if (r.spam) r.sub = "Scam · " + r.sub;
                        rows.add(r);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (cur != null) cur.close();
            }
        }
        return rows;
    }

    private static String typeLabel(int t) {
        switch (t) {
            case CallLog.Calls.INCOMING_TYPE: return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE: return "Outgoing";
            case CallLog.Calls.MISSED_TYPE: return "Missed";
            case CallLog.Calls.REJECTED_TYPE: return "Declined";
            case CallLog.Calls.BLOCKED_TYPE: return "Blocked";
            default: return "Call";
        }
    }

    /** First letter of a row title for the avatar bubble; "?" when there is none.
     *  Hoisted out of the adapters so scrolling does not allocate a substring plus an
     *  upper-cased copy of it for every bind. */
    private static String initialOf(String s) {
        if (s == null || s.isEmpty()) return "?";
        return String.valueOf(Character.toUpperCase(s.charAt(0)));
    }

    private static int typeIcon(int t) {
        switch (t) {
            case CallLog.Calls.INCOMING_TYPE: return R.drawable.ic_arrow_in;
            case CallLog.Calls.MISSED_TYPE: return R.drawable.ic_missed;
            case CallLog.Calls.REJECTED_TYPE: return R.drawable.ic_missed;
            case CallLog.Calls.BLOCKED_TYPE: return R.drawable.ic_missed;
            default: return R.drawable.ic_arrow_out;
        }
    }

    class CallRow {
        String title, sub, time, number;
        /** Avatar letter, computed once per row instead of on every list bind. */
        String initial;
        long id;
        int iconRes;
        boolean spam;
    }

    class RecentsAdapter extends BaseAdapter {
        final List<CallRow> items = new ArrayList<>();
        /** Switching back to this tab re-queries the log; when the rows are unchanged
         *  skip notifyDataSetChanged so no visible row is rebound for nothing. */
        void set(List<CallRow> r) {
            if (same(r)) return;
            items.clear(); items.addAll(r); notifyDataSetChanged();
        }
        private boolean same(List<CallRow> r) {
            int n = r.size();
            if (n != items.size()) return false;
            for (int i = 0; i < n; i++) {
                CallRow a = items.get(i), b = r.get(i);
                if (a.id != b.id || a.iconRes != b.iconRes || a.spam != b.spam) return false;
                if (!a.title.equals(b.title) || !a.sub.equals(b.sub) || !a.time.equals(b.time)) return false;
            }
            return true;
        }
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }
        /** Row views looked up once per inflated view instead of on every bind. */
        class Holder {
            ImageView dir;
            TextView name, sub, time, avatar;
            CallRow row;
            /** Last values applied, so a recycled row being rebound to another call
             *  skips setImageResource (which re-resolves and re-inflates the vector
             *  drawable) and setTextColor when the direction icon / spam colour is
             *  unchanged. During a fling most rebinds keep both, so the bind path
             *  stops allocating a Drawable per row. */
            int iconRes;
            ColorStateList nameColor;
        }

        public View getView(int i, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                cv = getLayoutInflater().inflate(R.layout.item_call, parent, false);
                h = new Holder();
                h.dir = cv.findViewById(R.id.call_dir);
                h.name = cv.findViewById(R.id.call_name);
                h.sub = cv.findViewById(R.id.call_sub);
                h.time = cv.findViewById(R.id.call_time);
                h.avatar = cv.findViewById(R.id.call_avatar);
                final Holder fh = h;
                cv.findViewById(R.id.call_action).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { if (fh.row != null) placeCall(fh.row.number); }
                });
                cv.setClickable(true);
                cv.setOnTouchListener(new SwipeRowTouchListener(MainActivity.this,
                        new SwipeRowTouchListener.Callbacks() {
                            public void onSwipeRight() { if (fh.row != null) placeCall(fh.row.number); }
                            public void onSwipeLeft() { if (fh.row != null) deleteCall(fh.row.id); }
                            public void onTap() { if (fh.row != null) placeCall(fh.row.number); }
                        }, R.drawable.swipe_call, R.drawable.swipe_delete));
                cv.setTag(h);
            } else {
                h = (Holder) cv.getTag();
            }
            CallRow r = items.get(i);
            h.row = r;
            if (h.iconRes != r.iconRes) {
                h.dir.setImageResource(r.iconRes);
                h.iconRes = r.iconRes;
            }
            h.name.setText(r.title);
            final ColorStateList color = r.spam ? NAME_SPAM : NAME_NORMAL;
            if (h.nameColor != color) {
                h.name.setTextColor(color);
                h.nameColor = color;
            }
            h.sub.setText(r.sub);
            h.time.setText(r.time);
            h.avatar.setText(r.initial);
            return cv;
        }
    }

    /** The delete is a provider write (disk, possibly a sync adapter), so it runs on the
     *  I/O thread; only the toast and the reload hop back to the UI thread. */
    private void deleteCall(final long id) {
        ioExecutor.execute(new Runnable() {
            public void run() {
                int n = -1;
                try {
                    n = getContentResolver().delete(CallLog.Calls.CONTENT_URI,
                            CallLog.Calls._ID + "=?", new String[]{String.valueOf(id)});
                } catch (Exception ignored) {
                }
                final int deleted = n;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (deleted > 0) { toast("Call deleted"); loadCalls(); }
                        else if (deleted == 0) toast("Couldn't delete call");
                        else toast("Couldn't delete — allow the Call log permission");
                    }
                });
            }
        });
    }

    private void confirmDeleteContact(final ContactRow row) {
        new AlertDialog.Builder(this)
                .setTitle("Delete contact")
                .setMessage("Remove " + row.name + " from your contacts?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { deleteContact(row); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Same as deleteCall: the provider write happens off the UI thread. */
    private void deleteContact(final ContactRow row) {
        final long cid = row.contactId;
        ioExecutor.execute(new Runnable() {
            public void run() {
                int n = -1;
                try {
                    Uri u = ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI, cid);
                    n = getContentResolver().delete(u, null, null);
                } catch (Exception ignored) {
                }
                final int deleted = n;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (deleted > 0) {
                            toast("Contact deleted");
                            loadContacts(search == null ? "" : search.getText().toString());
                        } else if (deleted == 0) {
                            toast("Couldn't delete contact");
                        } else {
                            toast("Couldn't delete — allow the Contacts permission");
                        }
                    }
                });
            }
        });
    }

    // ---------------- contacts ----------------

    private void setupContacts() {
        contactsList = vContacts.findViewById(R.id.contacts_list);
        contactsEmpty = vContacts.findViewById(R.id.contacts_empty);
        search = vContacts.findViewById(R.id.contact_search);
        contactsAdapter = new ContactsAdapter();
        contactsList.setAdapter(contactsAdapter);
        contactsList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                placeCall(contactsAdapter.items.get(pos).number);
            }
        });
        View addBtn = vContacts.findViewById(R.id.btn_new_contact);
        if (addBtn != null) addBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showNewContactDialog(""); }
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                // Debounce: one contacts query after typing settles, not one per keystroke.
                final String q = s.toString();
                if (searchPending != null) searchHandler.removeCallbacks(searchPending);
                searchPending = new Runnable() {
                    public void run() {
                        searchPending = null;
                        loadContacts(q);
                    }
                };
                searchHandler.postDelayed(searchPending, 220);
            }
            public void afterTextChanged(Editable s) { }
        });
        // Same as the recents tab: the contacts tab is hidden at launch, so walking
        // the address book here only added a second provider scan to cold start.
        // showTab(1) runs loadContacts() when the tab is first opened.
    }

    /** Kicks off the query on a background thread; the list is updated when it returns. */
    private void loadContacts(String filter) {
        final String f = filter == null ? "" : filter.toLowerCase().trim();
        // Same coalescing as loadCalls(): one in-flight query per filter is enough.
        if (contactsLoading && f.equals(contactsLoadingFilter)) return;
        contactsLoading = true;
        contactsLoadingFilter = f;
        final int token = ++contactsToken;
        ioExecutor.execute(new Runnable() {
            public void run() {
                final List<ContactRow> rows = queryContacts(f);
                runOnUiThread(new Runnable() {
                    public void run() {
                        // Drop results for an older search, or a dead activity.
                        if (token != contactsToken) return;
                        contactsLoading = false;
                        if (isFinishing() || isDestroyed()) return;
                        contactsAdapter.set(rows);
                        contactsEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private List<ContactRow> queryContacts(String f) {
        List<ContactRow> rows = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            Cursor cur = null;
            try {
                cur = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                if (cur != null) {
                    while (cur.moveToNext()) {
                        long cid = cur.getLong(0);
                        String name = cur.getString(1);
                        String num = cur.getString(2);
                        if (name == null) name = "";
                        if (!f.isEmpty()) {
                            // Match in place: toLowerCase() on the name and the number
                            // built two throwaway Strings for every contact row on every
                            // keystroke, which is pure garbage for a filtered list.
                            if (!containsIgnoreCase(name, f) && !containsIgnoreCase(num, f)) continue;
                        }
                        ContactRow r = new ContactRow();
                        r.contactId = cid;
                        r.name = name.isEmpty() ? "Unknown" : name;
                        r.number = num == null ? "" : num;
                        r.initial = initialOf(r.name);
                        rows.add(r);
                        if (rows.size() >= 500) break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (cur != null) cur.close();
            }
        }
        return rows;
    }

    /** Case-insensitive "contains" with no allocation: regionMatches compares in place,
     *  so a search keystroke over a full address book allocates nothing per row. */
    private static boolean containsIgnoreCase(String hay, String needle) {
        if (hay == null) return false;
        int nl = needle.length();
        if (nl == 0) return true;
        int last = hay.length() - nl;
        for (int i = 0; i <= last; i++) {
            if (hay.regionMatches(true, i, needle, 0, nl)) return true;
        }
        return false;
    }

    class ContactRow {
        String name, number;
        /** Avatar letter, computed once per row instead of on every list bind. */
        String initial;
        long contactId;
    }

    class ContactsAdapter extends BaseAdapter {
        final List<ContactRow> items = new ArrayList<>();
        /** Same as the recents adapter: no rebind when the result set did not change. */
        void set(List<ContactRow> r) {
            if (same(r)) return;
            items.clear(); items.addAll(r); notifyDataSetChanged();
        }
        private boolean same(List<ContactRow> r) {
            int n = r.size();
            if (n != items.size()) return false;
            for (int i = 0; i < n; i++) {
                ContactRow a = items.get(i), b = r.get(i);
                if (a.contactId != b.contactId) return false;
                if (!a.name.equals(b.name) || !a.number.equals(b.number)) return false;
            }
            return true;
        }
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }
        /** Row views looked up once per inflated view instead of on every bind. */
        class Holder {
            TextView name, number, avatar;
            ContactRow row;
        }

        public View getView(int i, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                cv = getLayoutInflater().inflate(R.layout.item_contact, parent, false);
                h = new Holder();
                h.name = cv.findViewById(R.id.contact_name);
                h.number = cv.findViewById(R.id.contact_number);
                h.avatar = cv.findViewById(R.id.contact_avatar);
                final Holder fh = h;
                cv.findViewById(R.id.contact_call).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { if (fh.row != null) placeCall(fh.row.number); }
                });
                cv.setClickable(true);
                cv.setOnTouchListener(new SwipeRowTouchListener(MainActivity.this,
                        new SwipeRowTouchListener.Callbacks() {
                            public void onSwipeRight() { if (fh.row != null) placeCall(fh.row.number); }
                            public void onSwipeLeft() { if (fh.row != null) confirmDeleteContact(fh.row); }
                            public void onTap() { if (fh.row != null) placeCall(fh.row.number); }
                        }, R.drawable.swipe_call, R.drawable.swipe_delete));
                cv.setTag(h);
            } else {
                h = (Holder) cv.getTag();
            }
            ContactRow r = items.get(i);
            h.row = r;
            h.name.setText(r.name);
            h.number.setText(r.number);
            h.avatar.setText(r.initial);
            return cv;
        }
    }

    // ---------------- shield ----------------

    private void setupShield() {
        screenStatus = vShield.findViewById(R.id.screen_status);
        shieldPill = vShield.findViewById(R.id.shield_pill);
        blockCount = vShield.findViewById(R.id.block_count);
        guardStatus = vShield.findViewById(R.id.guard_status);
        guardSwitch = vShield.findViewById(R.id.guard_switch);
        dialerStatus = vShield.findViewById(R.id.dialer_status);

        vShield.findViewById(R.id.btn_screen_role).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestScreeningRole(); }
        });
        vShield.findViewById(R.id.btn_default_dialer).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestDefaultDialer(); }
        });

        final EditText input = vShield.findViewById(R.id.block_input);
        vShield.findViewById(R.id.btn_block_add).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String n = input.getText().toString().trim();
                if (n.isEmpty()) { toast("Enter a number"); return; }
                engine.block(n);
                input.setText("");
                refreshShield();
                toast("Blocked " + n);
            }
        });
        vShield.findViewById(R.id.btn_manage_block).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showBlocklist(); }
        });
        vShield.findViewById(R.id.btn_admin).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestAdmin(); }
        });
        vShield.findViewById(R.id.btn_alarm).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { TheftGuardService.triggerAlarm(MainActivity.this, "Manual test alarm"); }
        });
        vShield.findViewById(R.id.btn_lock).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { lockNow(); }
        });

        guardSwitch.setChecked(prefs().getBoolean("guard_enabled", false));
        guardSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton btn, boolean on) {
                prefs().edit().putBoolean("guard_enabled", on).apply();
                if (on) { TheftGuardService.start(MainActivity.this); toast("Theft Guard on"); }
                else { TheftGuardService.stop(MainActivity.this); toast("Theft Guard off"); }
                refreshShield();
            }
        });
        refreshShield();
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("diller", MODE_PRIVATE);
    }

    /** Takes the already-resolved answer: isDefaultDialer() is a synchronous binder
     *  round trip to the RoleManager, and the shield refresh asked it twice. */
    private void updateBanner(boolean defaultDialer) {
        if (dialerBanner != null)
            dialerBanner.setVisibility(defaultDialer ? View.GONE : View.VISIBLE);
    }

    private void refreshShield() {
        // Resolve the dialer role once and share it with the banner and the status line;
        // this used to cost two binder calls per resume / tab switch.
        boolean dialer = isDefaultDialer();
        updateBanner(dialer);
        // Everything below only paints the Shield page. onResume() calls this on every
        // resume — including the first one at cold start — but the Shield page is built
        // lazily, so until that tab has been opened these fields are still null and the
        // work is thrown away: a RoleManager binder call, a blocklist read and a
        // DevicePolicyManager binder call on the startup path. showTab(3) refreshes the
        // page as soon as it is created, so it is never shown with stale text.
        if (vShield == null) return;
        boolean roleHeld = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null) roleHeld = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        }
        if (screenStatus != null) {
            screenStatus.setText(roleHeld
                    ? "Status: active — screening every incoming call"
                    : "Status: not enabled");
            screenStatus.setTextColor(roleHeld ? 0xFF34C759 : 0xFFFFC24B);
        }
        if (shieldPill != null) {
            shieldPill.setText(roleHeld ? "Shield ON" : "Shield OFF");
            shieldPill.setTextColor(roleHeld ? BLUE : 0xFFFFC24B);
        }

        if (dialerStatus != null) {
            dialerStatus.setText(dialer
                    ? "Default phone app: Diller — glass call screen on"
                    : "Default phone app: not set");
            dialerStatus.setTextColor(dialer ? 0xFF34C759 : 0xFFFFC24B);
        }

        // Count without copying: refreshShield runs on every resume and tab switch,
        // and blocklist() handed back a fresh copy of the whole set each time.
        int blocked = engine.blocklistSize();
        if (blockCount != null)
            blockCount.setText(blocked + " number" + (blocked == 1 ? "" : "s") + " blocked");

        ComponentName cn = new ComponentName(this, AdminReceiver.class);
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        boolean admin = dpm != null && dpm.isAdminActive(cn);
        if (guardStatus != null) {
            guardStatus.setText(admin
                    ? "Device admin: enabled — lock & alarm ready"
                    : "Device admin: not enabled");
            guardStatus.setTextColor(admin ? 0xFF34C759 : 0xFFFFC24B);
        }
    }

    private void showBlocklist() {
        final Set<String> bl = engine.blocklist();
        final String[] arr = bl.toArray(new String[0]);
        if (arr.length == 0) { toast("No numbers blocked yet"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Blocked numbers")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        engine.unblock(arr[which]);
                        refreshShield();
                        toast("Unblocked " + arr[which]);
                    }
                })
                .setPositiveButton("Close", null)
                .show();
    }

    private boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        return false;
    }

    private void requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) { toast("Diller is already the default Phone app"); return; }
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_DIALER);
                return;
            }
        }
        // Fallback for ROMs without the role API
        try {
            Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            toast("This device does not allow changing the Phone app");
        }
    }

    private void requestScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) toast("Call screening already on");
                else startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_SCREEN);
                return;
            }
        }
        toast("Call screening needs Android 10 or newer");
    }

    private void requestAdmin() {
        ComponentName cn = new ComponentName(this, AdminReceiver.class);
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (dpm != null && dpm.isAdminActive(cn)) { toast("Device protection already on"); return; }
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Diller uses device admin to lock your screen and sound a loud alarm if your phone is stolen.");
        try { startActivity(i); } catch (Exception e) { toast("Unavailable on this device"); }
    }

    private void lockNow() {
        ComponentName cn = new ComponentName(this, AdminReceiver.class);
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (dpm != null && dpm.isAdminActive(cn)) {
            try { dpm.lockNow(); } catch (Exception e) { toast("Lock unavailable"); }
        } else {
            requestAdmin();
        }
    }

    // ---------------- permissions ----------------

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        String[] all = {Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_PHONE_STATE};
        for (String p : all)
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED)
            need.add("android.permission.POST_NOTIFICATIONS");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        // Only the tab the user can actually see reloads here. Both lists used to be
        // rebuilt on every permission result regardless of what was granted or which
        // tab was open, so the first launch (keypad tab, permission dialog answered)
        // kicked off a full call-log scan and a full address-book walk for two hidden
        // pages — the same I/O the keypad's first paint was waiting on. showTab() loads
        // a list the first time that tab is opened, so nothing looks different.
        if (current == 0) loadCalls();
        if (current == 1) loadContacts(search == null ? "" : search.getText().toString());
        updateDisplay();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        refreshShield();
    }

    @Override
    protected void onDestroy() {
        if (searchPending != null) searchHandler.removeCallbacks(searchPending);
        searchPending = null;
        if (dialPending != null) searchHandler.removeCallbacks(dialPending);
        dialPending = null;
        contactsToken++;            // ignore any query still in flight
        callsToken++;
        summaryToken++;
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShield();   // also refreshes the default-dialer banner
        updateDisplay();
        if (current == 0) loadCalls();
        if (current == 1) loadContacts(search == null ? "" : search.getText().toString());
    }


    // ---------------- AI ----------------

    private void setupAi() {
        final TextView summary = vAi.findViewById(R.id.ai_summary);
        vAi.findViewById(R.id.btn_summary_refresh).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { loadAiSummary(summary); }
        });

        final EditText input = vAi.findViewById(R.id.ai_input);
        final TextView reply = vAi.findViewById(R.id.ai_reply);
        vAi.findViewById(R.id.btn_ask).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String q = input.getText().toString().trim();
                if (q.isEmpty()) return;
                if (!new AiConfig(MainActivity.this).hasKey()) {
                    reply.setText("Add your AI API key first - tap \"AI settings (API key)\" below.");
                    return;
                }
                reply.setText("Thinking...");
                new AiClient(MainActivity.this).chat(q, new AiClient.Callback() {
                    public void onResult(String text) {
                        reply.setText(text);
                        AiHistory.add(MainActivity.this, "chat", "Asked AI", q);
                        refreshAi();
                    }
                });
            }
        });

        final EditText trIn = vAi.findViewById(R.id.tr_input);
        final TextView trOut = vAi.findViewById(R.id.tr_out);
        final TextView trLang = vAi.findViewById(R.id.tr_lang);
        final String[] langs = {"Hindi","Bengali","Tamil","Telugu","Marathi","Gujarati","Kannada","Malayalam"};
        final String[] codes = {"hi-IN","bn-IN","ta-IN","te-IN","mr-IN","gu-IN","kn-IN","ml-IN"};
        final int[] li = {0};
        trLang.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                li[0] = (li[0] + 1) % langs.length;
                trLang.setText(langs[li[0]]);
            }
        });
        vAi.findViewById(R.id.btn_translate).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String t = trIn.getText().toString().trim();
                if (t.isEmpty()) return;
                if (!new AiConfig(MainActivity.this).hasKey()) {
                    trOut.setText("Add your AI API key first.");
                    return;
                }
                trOut.setText("Translating...");
                new AiClient(MainActivity.this).translate(t, codes[li[0]], new AiClient.Callback() {
                    public void onResult(String text) {
                        trOut.setText(text);
                        AiHistory.add(MainActivity.this, "translate", "Translated to " + trLang.getText(), t);
                    }
                });
            }
        });

        Switch sw = vAi.findViewById(R.id.agent_switch);
        sw.setChecked(prefs().getBoolean("ai_agent", false));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs().edit().putBoolean("ai_agent", on).apply();
                refreshAi();
            }
        });
        vAi.findViewById(R.id.btn_ai_settings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAiSettings(); }
        });
        // No refreshAi() here on purpose. The AI tab is hidden at launch (the keypad
        // is the default page), yet this used to render the AI history from prefs and
        // kick off the daily-summary call-log query during onCreate — UI-thread JSON
        // parsing plus a third content-provider query on every cold start, for a page
        // the user often never opens. showTab(4) already calls refreshAi() the first
        // time the tab is actually shown, so the tab looks exactly the same when seen.
    }

    private void refreshAi() {
        if (vAi == null) return;
        boolean on = prefs().getBoolean("ai_agent", false);
        TextView st = vAi.findViewById(R.id.agent_status);
        st.setText(on ? "AI agent ON - will answer unknown / suspected-spam calls."
                      : "AI agent off.");
        TextView hist = vAi.findViewById(R.id.ai_history);
        hist.setText(AiHistory.render(this));
        TextView sum = vAi.findViewById(R.id.ai_summary);
        String cur = sum.getText().toString();
        if (cur.equals("Tap refresh") || cur.trim().isEmpty()) loadAiSummary(sum);
    }

    /** Builds the daily summary on the I/O thread. The call-log query used to run on the
     *  UI thread on every visit to the AI tab, stalling the tab-switch animation. */
    private void loadAiSummary(final TextView sum) {
        if (sum == null) return;
        final int token = ++summaryToken;
        ioExecutor.execute(new Runnable() {
            public void run() {
                final String text = DailySummary.build(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        // Drop results for an older request, or a dead activity.
                        if (token != summaryToken || isFinishing() || isDestroyed()) return;
                        sum.setText(text);
                    }
                });
            }
        });
    }

    private void showAiSettings() {
        final AiConfig cfg = new AiConfig(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);

        final EditText keyIn = field(cfg.key(), "API key");
        final EditText provIn = field(cfg.provider(), "provider: sarvam / openai");
        final EditText baseIn = field(cfg.baseUrl(), "base URL");
        final EditText modelIn = field(cfg.model(), "model");
        box.addView(keyIn); box.addView(provIn); box.addView(baseIn); box.addView(modelIn);

        new AlertDialog.Builder(this)
                .setTitle("AI settings")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cfg.setKey(keyIn.getText().toString().trim());
                        cfg.setProvider(provIn.getText().toString().trim());
                        cfg.setBaseUrl(baseIn.getText().toString().trim());
                        cfg.setModel(modelIn.getText().toString().trim());
                        toast("AI settings saved");
                        refreshAi();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(WHITE);
        e.setHintTextColor(0xFF8E8E93);
        return e;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
