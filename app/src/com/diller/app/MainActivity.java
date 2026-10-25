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
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final int REQ_SCREEN = 11;
    private static final int REQ_DIALER = 12;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0A84FF;
    private static final int RED = 0xFFFF453A;

    private FrameLayout content;
    private View navCalls, navContacts, navKeypad, navShield;
    private View vCalls, vContacts, vKeypad, vShield;
    private int current = 2; // Keypad by default, like the iOS Phone app

    private SpamEngine engine;

    // keypad
    private TextView dialDisplay, dialSub, dialChip;
    private ImageView btnBack, btnAdd;
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

        vCalls = getLayoutInflater().inflate(R.layout.view_recents, content, false);
        vContacts = getLayoutInflater().inflate(R.layout.view_contacts, content, false);
        vKeypad = getLayoutInflater().inflate(R.layout.view_keypad, content, false);
        vShield = getLayoutInflater().inflate(R.layout.view_shield, content, false);
        content.addView(vCalls);
        content.addView(vContacts);
        content.addView(vKeypad);
        content.addView(vShield);

        setupKeypad();
        setupCalls();
        setupContacts();
        setupShield();

        navCalls.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(0); } });
        navContacts.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(1); } });
        navKeypad.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(2); } });
        navShield.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(3); } });

        showTab(2);
        handleIntent(getIntent());
        requestPerms();
    }

    // ---------------- tabs ----------------

    private void showTab(int i) {
        current = i;
        View[] views = {vCalls, vContacts, vKeypad, vShield};
        for (int k = 0; k < views.length; k++)
            views[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        styleNav(navCalls, i == 0);
        styleNav(navContacts, i == 1);
        styleNav(navKeypad, i == 2);
        styleNav(navShield, i == 3);
        if (i == 0) loadCalls();
        if (i == 1) loadContacts(search == null ? "" : search.getText().toString());
        if (i == 3) refreshShield();
    }

    private void styleNav(View item, boolean sel) {
        if (!(item instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) item;
        if (g.getChildCount() >= 2) {
            item.setBackgroundResource(sel ? R.drawable.nav_selected : 0);
            tint(g.getChildAt(0), sel ? BLUE : WHITE);
            View lb = g.getChildAt(1);
            if (lb instanceof TextView) ((TextView) lb).setTextColor(sel ? BLUE : WHITE);
        } else {
            tint(g.getChildAt(0), sel ? BLUE : WHITE);
        }
    }

    private void tint(View v, int color) {
        if (v instanceof ImageView) ((ImageView) v).setColorFilter(color);
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
            lp.setMargins(dp(14), dp(10), dp(14), dp(10));

            FrameLayout key = new FrameLayout(this);
            key.setLayoutParams(lp);
            key.setBackgroundResource(R.drawable.key_bg);

            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView d = new TextView(this);
            d.setText(digits[i]);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            d.setTextColor(WHITE);
            d.setGravity(Gravity.CENTER);
            inner.addView(d);

            if (subs[i].length() > 0) {
                TextView s = new TextView(this);
                s.setText(subs[i]);
                s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                s.setTextColor(0xFF8E8E93);
                s.setGravity(Gravity.CENTER);
                s.setLetterSpacing(0.12f);
                inner.addView(s);
            }
            key.addView(inner);

            final String val = digits[i];
            key.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { append(val); }
            });
            if ("0".equals(digits[i])) {
                key.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { append("+"); return true; }
                });
            }
            g.addView(key);
        }
        host.addView(g);
    }

    private void append(String s) { typed.append(s); updateDisplay(); }

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

        String name = Contacts.nameFor(this, num);
        dialSub.setText(name != null ? name : "");

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
                        String nm = nameIn.getText().toString().trim();
                        String num = numIn.getText().toString().trim();
                        if (nm.isEmpty() || num.isEmpty()) { toast("Name and number required"); return; }
                        if (saveContact(nm, num)) {
                            toast("Contact saved");
                            if (current == 1) loadContacts(search == null ? "" : search.getText().toString());
                            updateDisplay();
                        } else {
                            toast("Couldn't save — allow the Contacts permission");
                        }
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
        loadCalls();
    }

    private void loadCalls() {
        List<CallRow> rows = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG)
                == PackageManager.PERMISSION_GRANTED) {
            Cursor cur = null;
            try {
                cur = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                        new String[]{CallLog.Calls._ID, CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                        null, null, CallLog.Calls.DATE + " DESC");
                if (cur != null) {
                    while (cur.moveToNext() && rows.size() < 250) {
                        long callId = cur.getLong(0);
                        String name = cur.getString(1);
                        String num = cur.getString(2);
                        int type = cur.getInt(3);
                        long date = cur.getLong(4);
                        if (missedOnly && type != CallLog.Calls.MISSED_TYPE) continue;

                        CallRow r = new CallRow();
                        r.id = callId;
                        r.number = num == null ? "" : num;
                        boolean hasName = name != null && !name.isEmpty();
                        r.title = hasName ? name : (r.number.isEmpty() ? "Unknown" : r.number);
                        r.sub = hasName && !r.number.isEmpty() ? r.number : typeLabel(type);
                        r.iconRes = typeIcon(type);
                        r.time = DateUtils.getRelativeTimeSpanString(date).toString();
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
        recentsAdapter.set(rows);
        recentsEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
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
        long id;
        int iconRes;
        boolean spam;
    }

    class RecentsAdapter extends BaseAdapter {
        final List<CallRow> items = new ArrayList<>();
        void set(List<CallRow> r) { items.clear(); items.addAll(r); notifyDataSetChanged(); }
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup parent) {
            if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_call, parent, false);
            CallRow r = items.get(i);
            ((ImageView) cv.findViewById(R.id.call_dir)).setImageResource(r.iconRes);
            TextView nm = cv.findViewById(R.id.call_name);
            nm.setText(r.title);
            nm.setTextColor(r.spam ? RED : WHITE);
            ((TextView) cv.findViewById(R.id.call_sub)).setText(r.sub);
            ((TextView) cv.findViewById(R.id.call_time)).setText(r.time);
            String init = r.title.isEmpty() ? "?" : r.title.substring(0, 1).toUpperCase();
            ((TextView) cv.findViewById(R.id.call_avatar)).setText(init);
            final String num = r.number;
            cv.findViewById(R.id.call_action).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { placeCall(num); }
            });
            final CallRow row = r;
            cv.setOnTouchListener(new SwipeRowTouchListener(MainActivity.this,
                    new SwipeRowTouchListener.Callbacks() {
                        public void onSwipeRight() { placeCall(row.number); }
                        public void onSwipeLeft() { deleteCall(row.id); }
                    }, R.drawable.swipe_call, R.drawable.swipe_delete));
            return cv;
        }
    }

    private void deleteCall(long id) {
        try {
            int n = getContentResolver().delete(CallLog.Calls.CONTENT_URI,
                    CallLog.Calls._ID + "=?", new String[]{String.valueOf(id)});
            if (n > 0) { toast("Call deleted"); loadCalls(); }
            else toast("Couldn't delete call");
        } catch (Exception e) {
            toast("Couldn't delete — allow the Call log permission");
        }
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

    private void deleteContact(ContactRow row) {
        try {
            Uri u = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, row.contactId);
            int n = getContentResolver().delete(u, null, null);
            if (n > 0) {
                toast("Contact deleted");
                loadContacts(search == null ? "" : search.getText().toString());
            } else {
                toast("Couldn't delete contact");
            }
        } catch (Exception e) {
            toast("Couldn't delete — allow the Contacts permission");
        }
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
            public void onTextChanged(CharSequence s, int a, int b, int c) { loadContacts(s.toString()); }
            public void afterTextChanged(Editable s) { }
        });
        loadContacts("");
    }

    private void loadContacts(String filter) {
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
                String f = filter == null ? "" : filter.toLowerCase().trim();
                if (cur != null) {
                    while (cur.moveToNext()) {
                        long cid = cur.getLong(0);
                        String name = cur.getString(1);
                        String num = cur.getString(2);
                        if (name == null) name = "";
                        if (!f.isEmpty() && !name.toLowerCase().contains(f)
                                && (num == null || !num.toLowerCase().contains(f))) continue;
                        ContactRow r = new ContactRow();
                        r.contactId = cid;
                        r.name = name.isEmpty() ? "Unknown" : name;
                        r.number = num == null ? "" : num;
                        rows.add(r);
                        if (rows.size() >= 500) break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (cur != null) cur.close();
            }
        }
        contactsAdapter.set(rows);
        contactsEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    class ContactRow {
        String name, number;
        long contactId;
    }

    class ContactsAdapter extends BaseAdapter {
        final List<ContactRow> items = new ArrayList<>();
        void set(List<ContactRow> r) { items.clear(); items.addAll(r); notifyDataSetChanged(); }
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup parent) {
            if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_contact, parent, false);
            ContactRow r = items.get(i);
            ((TextView) cv.findViewById(R.id.contact_name)).setText(r.name);
            ((TextView) cv.findViewById(R.id.contact_number)).setText(r.number);
            String init = r.name.isEmpty() ? "?" : r.name.substring(0, 1).toUpperCase();
            ((TextView) cv.findViewById(R.id.contact_avatar)).setText(init);
            final String num = r.number;
            cv.findViewById(R.id.contact_call).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { placeCall(num); }
            });
            final ContactRow row = r;
            cv.setOnTouchListener(new SwipeRowTouchListener(MainActivity.this,
                    new SwipeRowTouchListener.Callbacks() {
                        public void onSwipeRight() { placeCall(row.number); }
                        public void onSwipeLeft() { confirmDeleteContact(row); }
                    }, R.drawable.swipe_call, R.drawable.swipe_delete));
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

    private void updateBanner() {
        if (dialerBanner != null)
            dialerBanner.setVisibility(isDefaultDialer() ? View.GONE : View.VISIBLE);
    }

    private void refreshShield() {
        updateBanner();
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

        boolean dialer = isDefaultDialer();
        if (dialerStatus != null) {
            dialerStatus.setText(dialer
                    ? "Default phone app: Diller — glass call screen on"
                    : "Default phone app: not set");
            dialerStatus.setTextColor(dialer ? 0xFF34C759 : 0xFFFFC24B);
        }

        Set<String> bl = engine.blocklist();
        if (blockCount != null)
            blockCount.setText(bl.size() + " number" + (bl.size() == 1 ? "" : "s") + " blocked");

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
        loadCalls();
        loadContacts(search == null ? "" : search.getText().toString());
        updateDisplay();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        refreshShield();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShield();
        updateBanner();
        updateDisplay();
        if (current == 0) loadCalls();
        if (current == 1) loadContacts(search == null ? "" : search.getText().toString());
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
