package com.fireremote.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final int ST_IDLE = 0, ST_BUSY = 1, ST_OK = 2, ST_ERROR = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private SharedPreferences prefs;

    private volatile LightningClient tv;
    private volatile boolean busy;      // connecting / pairing in progress
    private volatile boolean tvAsleep;
    private AlertDialog pinDialog;

    // Optional ADB "typing link".
    private AdbKeys adbKeys;
    private volatile AdbClient adb;
    private volatile boolean adbConnecting;
    private TextView typingStatus;
    private TextView typingButton;

    private View statusDot;
    private TextView statusText;
    private LinearLayout connectCard;
    private EditText ipField;
    private TextView connectBtn;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        setContentView(buildUi());

        String ip = prefs.getString("ip", "");
        ipField.setText(ip);
        updateTypingCard();
        if (ip.length() > 0) {
            connect();
            if (prefs.getBoolean("adb_enabled", false)) connectTypingLink(false);
        } else {
            setStatus(ST_IDLE, "Not connected — tap here to connect");
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showConnect();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AdbClient a = adb;
        if (a != null) a.close();
        final LightningClient t = tv;
        if (t != null) {
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    t.close();
                }
            });
        }
        exec.shutdown();
    }

    // ================================================================== UI construction

    private View buildUi() {
        // Everything fits on one screen; the touchpad / D-pad takes whatever height is left.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);
        int pad = dpi(16);
        root.setPadding(pad, dpi(10), pad, dpi(14));

        root.addView(buildHeader(), lp(-1, -2));

        // Pop-ups for the less-used things
        connectCard = buildConnectCard();
        connectDialog = sheet("Connect to Fire TV", connectCard);
        typingDialog = sheet("Volume & typing link", buildTypingCard());

        // Navigation: swipe touchpad or classic D-pad, switchable.
        final String[] navMap = {"dpad_up", "dpad_right", "dpad_down", "dpad_left", "select"};
        final DpadView dpad = new DpadView(this);
        dpad.setListener(new DpadView.Listener() {
            @Override
            public void onKey(int which) {
                key(navMap[which]);
            }
        });
        final TouchpadView touchpad = new TouchpadView(this);
        touchpad.setListener(new TouchpadView.Listener() {
            @Override
            public void onKey(int which) {
                key(navMap[which]);
            }

            @Override
            public void onLongPress() {
                key("menu");
            }
        });

        LinearLayout seg = row();
        seg.setBackground(rounded(Theme.SURFACE, 18));
        seg.setPadding(dpi(3), dpi(3), dpi(3), dpi(3));
        final TextView segPad = segment("Touchpad");
        final TextView segDpad = segment("D-pad");
        seg.addView(segPad, lp(0, dpi(30), 0, 0, 0, 0, 1f));
        seg.addView(segDpad, lp(0, dpi(30), 0, 0, 0, 0, 1f));
        root.addView(seg, lp(-1, -2, 56, 10, 56, 0));

        final Runnable[] apply = new Runnable[1];
        apply[0] = new Runnable() {
            @Override
            public void run() {
                boolean usePad = prefs.getBoolean("touchpad", true);
                touchpad.setVisibility(usePad ? View.VISIBLE : View.GONE);
                dpad.setVisibility(usePad ? View.GONE : View.VISIBLE);
                styleSegment(segPad, usePad);
                styleSegment(segDpad, !usePad);
            }
        };
        segPad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("touchpad", true).apply();
                apply[0].run();
            }
        });
        segDpad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("touchpad", false).apply();
                apply[0].run();
            }
        });
        FrameLayout navArea = new FrameLayout(this);
        navArea.addView(touchpad, new FrameLayout.LayoutParams(-1, -1));
        navArea.addView(dpad, new FrameLayout.LayoutParams(-1, -1));
        root.addView(navArea, lp(-1, 0, 0, 10, 0, 0, 1f));
        apply[0].run();

        int h = dpi(52);
        root.addView(threeRow(
                new RemoteButton(this, Icons.BACK, null, false).describe("Back").onPress(keyPress("back")),
                new RemoteButton(this, Icons.HOME, null, false).describe("Home")
                        .onPress(new RemoteButton.OnPress() {
                            @Override
                            public void onPress() {
                                homePressed();
                            }
                        }),
                new RemoteButton(this, Icons.MENU, null, false).describe("Menu").onPress(keyPress("menu"))),
                lp(-1, h, 0, 12, 0, 0));
        root.addView(threeRow(
                new RemoteButton(this, Icons.REWIND, null, false).describe("Rewind")
                        .onPress(new RemoteButton.OnPress() {
                            @Override
                            public void onPress() {
                                scan(false);
                            }
                        }),
                new RemoteButton(this, Icons.PLAY_PAUSE, null, false).describe("Play or pause")
                        .colors(Theme.ACCENT, Theme.ACCENT_PRESSED, Theme.ON_ACCENT)
                        .onPress(new RemoteButton.OnPress() {
                            @Override
                            public void onPress() {
                                media("play", null, null);
                            }
                        }),
                new RemoteButton(this, Icons.FAST_FORWARD, null, false).describe("Fast forward")
                        .onPress(new RemoteButton.OnPress() {
                            @Override
                            public void onPress() {
                                scan(true);
                            }
                        })),
                lp(-1, h, 0, 10, 0, 0));
        root.addView(threeRow(
                new RemoteButton(this, Icons.VOL_DOWN, null, false).describe("Volume down")
                        .repeatWhileHeld().onPress(volumePress(false)),
                new RemoteButton(this, Icons.KEYBOARD, null, false).describe("Keyboard")
                        .onPress(new RemoteButton.OnPress() {
                            @Override
                            public void onPress() {
                                showKeyboard();
                            }
                        }),
                new RemoteButton(this, Icons.VOL_UP, null, false).describe("Volume up")
                        .repeatWhileHeld().onPress(volumePress(true))),
                lp(-1, h, 0, 10, 0, 0));
        favRow = row();
        root.addView(favRow, lp(-1, h, 0, 10, 0, 0));
        refreshFavourites();
        return root;
    }

    private View buildHeader() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Fire Remote", 21, Theme.TEXT, true));
        LinearLayout status = row();
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Theme.MUTED);
        statusDot.setBackground(dot);
        status.addView(statusDot, lp(dpi(8), dpi(8)));
        statusText = text("Not connected", 12.5f, Theme.MUTED, false);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.addView(statusText, lp(0, -2, 7, 0, 0, 0, 1f));
        titles.addView(status, lp(-1, -2, 0, 2, 0, 0));
        titles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConnect();
            }
        });
        header.addView(titles, lp(0, -2, 0, 0, 8, 0, 1f));

        header.addView(new RemoteButton(this, Icons.WIFI, null, true).describe("Connection")
                .onPress(new RemoteButton.OnPress() {
                    @Override
                    public void onPress() {
                        showConnect();
                    }
                }), lp(dpi(44), dpi(44)));
        header.addView(new RemoteButton(this, Icons.GEAR, null, true).describe("More options")
                .onPress(new RemoteButton.OnPress() {
                    @Override
                    public void onPress() {
                        showMoreMenu();
                    }
                }), lp(dpi(44), dpi(44), 8, 0, 0, 0));
        header.addView(new RemoteButton(this, Icons.POWER, null, true)
                .colors(0xFF2A1A1A, 0xFF3A2222, Theme.ERROR).describe("Sleep or wake")
                .onPress(new RemoteButton.OnPress() {
                    @Override
                    public void onPress() {
                        powerPressed();
                    }
                }), lp(dpi(44), dpi(44), 8, 0, 0, 0));
        return header;
    }

    private AlertDialog connectDialog;
    private AlertDialog typingDialog;

    /** A dialog wrapping a card view that is built once and re-shown. */
    private AlertDialog sheet(String title, View content) {
        content.setBackground(null);
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("Close", null)
                .create();
    }

    private void showConnect() {
        if (!isFinishing() && !connectDialog.isShowing()) connectDialog.show();
    }

    private void hideConnect() {
        if (connectDialog.isShowing()) connectDialog.dismiss();
    }

    private void showMoreMenu() {
        final boolean custom = prefs.getBoolean("custom_home", true);
        String[] items = {"Fire TV settings", "Volume & typing link", "Change Fire TV / re-pair",
                "Home button: " + (custom ? "ad-free Home" : "Amazon Home")};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("More")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) launch("Settings", "settings");
                        else if (which == 1) typingDialog.show();
                        else if (which == 2) showConnect();
                        else {
                            prefs.edit().putBoolean("custom_home", !custom).apply();
                            toast(!custom ? "Home opens your ad-free Home screen (if installed)."
                                    : "Home opens Amazon's home screen.");
                        }
                    }
                })
                .show();
    }

    private void showAppsSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dpi(18);
        box.setPadding(p, dpi(6), p, 0);
        TextView tip = text("Tip: hold a favourite at the bottom of the screen to change, move or remove it. "
                + "Tap + to add one (up to 4).", 12.5f, Theme.MUTED, false);
        box.addView(tip, lp(-1, -2, 0, 0, 0, 12));
        final AlertDialog[] holder = new AlertDialog[1];
        String[][] apps = {
                {"Prime Video", "com.amazon.cloud9", "com.amazon.avod"},
                {"Netflix", "com.netflix.ninja"},
                {"Disney+", "com.disney.disneyplus"},
                {"BBC iPlayer", "uk.co.bbc.iplayer", "bbc.iplayer.android"},
                {"Spotify", "com.spotify.tv.android"},
                {"YouTube", "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
                        "com.amazon.firetv.youtube.tv"},
        };
        LinearLayout r = null;
        for (int i = 0; i < apps.length; i++) {
            if (i % 2 == 0) {
                r = row();
                box.addView(r, lp(-1, dpi(50), 0, i == 0 ? 0 : 10, 0, 0));
            }
            String[] a = apps[i];
            String[] pkgs = new String[a.length - 1];
            System.arraycopy(a, 1, pkgs, 0, pkgs.length);
            final RemoteButton.OnPress open = appPress(a[0], pkgs);
            RemoteButton b = new RemoteButton(this, 0, a[0], false)
                    .colors(Theme.SURFACE_HIGH, Theme.SURFACE_PRESSED, Theme.TEXT)
                    .onPress(new RemoteButton.OnPress() {
                        @Override
                        public void onPress() {
                            open.onPress();
                            if (holder[0] != null) holder[0].dismiss();
                        }
                    });
            r.addView(b, lp(0, -1, i % 2 == 0 ? 0 : 5, 0, i % 2 == 0 ? 5 : 0, 0, 1f));
        }
        RemoteButton all = new RemoteButton(this, 0, "All installed apps…", false)
                .colors(Theme.SURFACE_HIGH, Theme.SURFACE_PRESSED, Theme.ACCENT)
                .onPress(new RemoteButton.OnPress() {
                    @Override
                    public void onPress() {
                        if (holder[0] != null) holder[0].dismiss();
                        showAllApps();
                    }
                });
        box.addView(all, lp(-1, dpi(50), 0, 10, 0, 0));
        holder[0] = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Apps")
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    private LinearLayout buildConnectCard() {
        LinearLayout card = card();
        card.addView(text("Fire TV IP address", 13, Theme.MUTED, false));
        ipField = new EditText(this);
        ipField.setHint("e.g. 192.168.1.50");
        styleField(ipField, 17);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipField.setImeOptions(EditorInfo.IME_ACTION_GO);
        ipField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                connect();
                return true;
            }
        });
        card.addView(ipField, lp(-1, -2, 0, 8, 0, 0));

        LinearLayout buttons = row();
        TextView find = pillButton("Find on Wi-Fi", false);
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanNetwork();
            }
        });
        connectBtn = pillButton("Connect", true);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        buttons.addView(find, lp(0, dpi(46), 0, 0, 5, 0, 1f));
        buttons.addView(connectBtn, lp(0, dpi(46), 5, 0, 0, 0, 1f));
        card.addView(buttons, lp(-1, -2, 0, 12, 0, 0));

        TextView help = text("Keep your phone on the same Wi-Fi as the Fire TV, then tap Find on Wi-Fi. "
                + "A 4-digit code will appear on the TV — type it in here. No settings to change on the TV.",
                12.5f, Theme.MUTED, false);
        help.setLineSpacing(0, 1.25f);
        card.addView(help, lp(-1, -2, 0, 12, 0, 0));
        return card;
    }

    private LinearLayout buildTypingCard() {
        LinearLayout card = card();
        card.addView(text("Volume & typing link", 15, Theme.TEXT, true));
        typingStatus = text("", 12.5f, Theme.MUTED, false);
        typingStatus.setLineSpacing(0, 1.25f);
        card.addView(typingStatus, lp(-1, -2, 0, 6, 0, 0));
        typingButton = pillButton("Set up", false);
        typingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (adb != null && adb.isConnected()) {
                    toast("Volume & typing link is connected.");
                } else {
                    showTypingSetup();
                }
            }
        });
        card.addView(typingButton, lp(-1, dpi(44), 0, 12, 0, 0));
        return card;
    }

    private LinearLayout threeRow(View a, View b, View c) {
        LinearLayout r = row();
        r.addView(a, lp(0, -1, 0, 0, 5, 0, 1f));
        r.addView(b, lp(0, -1, 5, 0, 5, 0, 1f));
        r.addView(c, lp(0, -1, 5, 0, 0, 0, 1f));
        return r;
    }

    private RemoteButton appChip(String name, String... pkgs) {
        return new RemoteButton(this, 0, name, false)
                .colors(Theme.SURFACE_HIGH, Theme.SURFACE_PRESSED, Theme.TEXT)
                .onPress(appPress(name, pkgs));
    }

    // ================================================================== connecting & pairing

    private void connect() {
        if (busy) return;
        String raw = ipField.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("Enter your Fire TV's IP address, or tap Find on Wi-Fi.");
            showConnect();
            return;
        }
        final String host = raw.contains(":") ? raw.substring(0, raw.indexOf(':')) : raw;
        prefs.edit().putString("ip", host).apply();
        final LightningClient old = tv;
        final LightningClient c = new LightningClient(host, prefs.getString("token_" + host, null));
        tv = c;
        busy = true;
        setStatus(ST_BUSY, "Connecting to " + host + "…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                if (old != null) old.close();
                if (c.token() == null) {
                    requestPin(c);
                    return;
                }
                try {
                    c.wake();
                    LightningClient.Response r = c.request("GET", "/v1/FireTV/status", null, 4000, true);
                    if (r.code == 401 || r.code == 403) {
                        requestPin(c);
                        return;
                    }
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            setConnected(host);
                        }
                    });
                } catch (IOException e) {
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            setStatus(ST_ERROR, "Can't reach the Fire TV at " + host
                                    + ". Is it switched on and on the same Wi-Fi?");
                            showConnect();
                        }
                    });
                }
            }
        });
    }

    /** Runs on the worker thread: asks the TV to show a PIN, then opens the PIN dialog. */
    private void requestPin(final LightningClient c) {
        onUi(new Runnable() {
            @Override
            public void run() {
                setStatus(ST_BUSY, "Asking the TV to show a code…");
            }
        });
        try {
            c.showPin("Fire Remote (" + Build.MODEL + ")");
            onUi(new Runnable() {
                @Override
                public void run() {
                    busy = false;
                    setStatus(ST_BUSY, "Enter the 4-digit code shown on your TV");
                    showPinDialog(c);
                }
            });
        } catch (final IOException e) {
            onUi(new Runnable() {
                @Override
                public void run() {
                    busy = false;
                    setStatus(ST_ERROR, "Couldn't reach the Fire TV at " + c.host
                            + ". Check the address and that it's on the same Wi-Fi.");
                    showConnect();
                }
            });
        }
    }

    private void showPinDialog(final LightningClient c) {
        if (isFinishing()) return;
        if (pinDialog != null && pinDialog.isShowing()) pinDialog.dismiss();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dpi(22);
        box.setPadding(p, dpi(8), p, 0);
        box.addView(text("Your TV is showing a 4-digit code. Type it below.", 14, Theme.MUTED, false));
        final EditText pin = new EditText(this);
        styleField(pin, 30);
        pin.setGravity(Gravity.CENTER);
        pin.setLetterSpacing(0.6f);
        pin.setHint("····");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        box.addView(pin, lp(-1, -2, 0, 16, 0, 0));
        final TextView error = text("", 13, Theme.ERROR, false);
        box.addView(error, lp(-1, -2, 0, 8, 0, 0));

        final AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pair with your Fire TV")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Pair", null)
                .setNeutralButton("New code", null)
                .setNegativeButton("Cancel", null)
                .create();
        pinDialog = d;

        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                final String code = pin.getText().toString().trim();
                if (code.length() != 4) {
                    error.setText("The code has 4 digits.");
                    return;
                }
                error.setTextColor(Theme.MUTED);
                error.setText("Checking…");
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        String token;
                        try {
                            token = c.verifyPin(code);
                        } catch (IOException e) {
                            token = null;
                        }
                        final String t = token;
                        onUi(new Runnable() {
                            @Override
                            public void run() {
                                if (t == null) {
                                    error.setTextColor(Theme.ERROR);
                                    error.setText("That code didn't match. Check the TV, or tap New code.");
                                    pin.setText("");
                                    return;
                                }
                                prefs.edit().putString("token_" + c.host, t).apply();
                                d.dismiss();
                                setConnected(c.host);
                                toast("Paired! You won't need a code again on this phone.");
                            }
                        });
                    }
                });
            }
        };
        pin.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c2) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 4) submit.run();
            }
        });
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        submit.run();
                    }
                });
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        error.setTextColor(Theme.MUTED);
                        error.setText("Showing a new code on the TV…");
                        exec.execute(new Runnable() {
                            @Override
                            public void run() {
                                boolean ok;
                                try {
                                    c.showPin("Fire Remote (" + Build.MODEL + ")");
                                    ok = true;
                                } catch (IOException e) {
                                    ok = false;
                                }
                                final boolean fOk = ok;
                                onUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        error.setTextColor(fOk ? Theme.MUTED : Theme.ERROR);
                                        error.setText(fOk ? "New code is on the TV." : "Couldn't reach the TV.");
                                    }
                                });
                            }
                        });
                    }
                });
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        setStatus(ST_IDLE, "Not paired — tap Connect to try again");
                        showConnect();
                    }
                });
            }
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        pin.requestFocus();
    }

    private void setConnected(String host) {
        setStatus(ST_OK, "Connected to " + host);
        hideConnect();
    }

    // ================================================================== sending commands

    private interface TvCall {
        LightningClient.Response run(LightningClient c) throws IOException;
    }

    /** Runs a command on the worker thread with consistent error handling. */
    private void send(final TvCall call, final Runnable onFailure) {
        final LightningClient c = tv;
        if (c == null || c.token() == null) {
            if (busy) {
                toast("Still connecting…");
            } else if (ipField.getText().length() > 0) {
                connect();
            } else {
                toast("Connect to your Fire TV first.");
                showConnect();
            }
            return;
        }
        if (pending.get() >= 10) return; // pressing faster than the TV can keep up
        pending.incrementAndGet();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LightningClient.Response r = call.run(c);
                    if (r.ok()) {
                        onUi(new Runnable() {
                            @Override
                            public void run() {
                                if (statusIsError) setConnected(c.host);
                            }
                        });
                    } else if (onFailure != null) {
                        onUi(onFailure);
                    }
                } catch (LightningClient.NotPairedException e) {
                    prefs.edit().remove("token_" + c.host).apply();
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            if (busy) return;
                            busy = true;
                            exec.execute(new Runnable() {
                                @Override
                                public void run() {
                                    requestPin(c);
                                }
                            });
                        }
                    });
                } catch (IOException e) {
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(ST_ERROR, "The Fire TV isn't responding. Is it on? Tap a button to retry.");
                        }
                    });
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
    }

    private void key(final String action) {
        send(new TvCall() {
            @Override
            public LightningClient.Response run(LightningClient c) throws IOException {
                return c.key(action);
            }
        }, null);
    }

    private RemoteButton.OnPress keyPress(final String action) {
        return new RemoteButton.OnPress() {
            @Override
            public void onPress() {
                key(action);
            }
        };
    }

    private void media(final String action, final String json, final String fallbackJson) {
        send(new TvCall() {
            @Override
            public LightningClient.Response run(LightningClient c) throws IOException {
                LightningClient.Response r = c.media(action, json);
                if (!r.ok() && fallbackJson != null) r = c.media(action, fallbackJson);
                return r;
            }
        }, null);
    }

    private void scan(boolean forward) {
        // Two body formats have been seen in the wild; try the official app's first.
        String primary = "{\"speed\":1,\"durationInSeconds\":10,\"direction\":\""
                + (forward ? "forward" : "backward") + "\"}";
        String fallback = "{\"direction\":\"" + (forward ? "forward" : "back")
                + "\",\"keyAction\":{\"keyActionType\":\"keyDown\"}}";
        media("scan", primary, fallback);
    }

    private boolean volumeHintShown;

    /** Volume needs the ADB link: the PIN connection has no working volume control. */
    private void volume(boolean up) {
        AdbClient a = adb;
        if (a != null && a.isConnected()) {
            a.shell("input keyevent " + (up ? 24 : 25));
            return;
        }
        key(up ? "volume_up" : "volume_down"); // harmless; works on some models
        if (!volumeHintShown && tv != null && tv.token() != null) {
            volumeHintShown = true;
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Volume needs one more step")
                    .setMessage("The Fire TV's code connection doesn't control volume — Amazon's own remote app "
                            + "has the same limit. Set up the volume & typing link (about a minute, using this "
                            + "app's touchpad) and the volume buttons will work.")
                    .setPositiveButton("Set up", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            showTypingSetup();
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
        }
    }

    private RemoteButton.OnPress volumePress(final boolean up) {
        return new RemoteButton.OnPress() {
            @Override
            public void onPress() {
                volume(up);
            }
        };
    }

    /** Package of the ad-free Home screen installed by the Fire Stick clean-up. */
    private static final String CUSTOM_HOME = "com.firehome.launcher";

    /**
     * Opens the ad-free Home app directly when it's installed. Sending Fire OS's own Home key
     * would show Amazon's home screen and make Android block other screens for 5 seconds.
     */
    private void homePressed() {
        final boolean tryCustom = prefs.getBoolean("custom_home", true);
        send(new TvCall() {
            @Override
            public LightningClient.Response run(LightningClient c) throws IOException {
                if (tryCustom) {
                    LightningClient.Response r = c.launchApp(CUSTOM_HOME);
                    if (r.ok()) return r;
                }
                return c.key("home");
            }
        }, null);
    }

    private void powerPressed() {
        if (!tvAsleep) {
            tvAsleep = true;
            key("sleep");
            toast("Putting the TV to sleep. Tap power again to wake it.");
        } else {
            tvAsleep = false;
            send(new TvCall() {
                @Override
                public LightningClient.Response run(LightningClient c) throws IOException {
                    c.wake();
                    return c.key("home");
                }
            }, null);
        }
    }

    private void launch(final String name, final String pkg) {
        send(new TvCall() {
            @Override
            public LightningClient.Response run(LightningClient c) throws IOException {
                return c.launchApp(pkg);
            }
        }, new Runnable() {
            @Override
            public void run() {
                toast("Couldn't open " + name + ".");
            }
        });
    }

    /** Tries each known package name; if none works, looks the app up by name in the TV's app list. */
    private RemoteButton.OnPress appPress(final String name, final String... pkgs) {
        return new RemoteButton.OnPress() {
            @Override
            public void onPress() {
                send(new TvCall() {
                    @Override
                    public LightningClient.Response run(LightningClient c) throws IOException {
                        String cached = prefs.getString("pkg_" + name, null);
                        if (cached != null) {
                            LightningClient.Response r = c.launchApp(cached);
                            if (r.ok()) return r;
                        }
                        LightningClient.Response last = null;
                        for (String p : pkgs) {
                            last = c.launchApp(p);
                            if (last.ok()) {
                                prefs.edit().putString("pkg_" + name, p).apply();
                                return last;
                            }
                        }
                        String needle = name.toLowerCase().replace("+", "");
                        for (String[] app : fetchApps(c)) {
                            if (app[0].toLowerCase().replace("+", "").contains(needle)) {
                                LightningClient.Response r = c.launchApp(app[1]);
                                if (r.ok()) {
                                    prefs.edit().putString("pkg_" + name, app[1]).apply();
                                    return r;
                                }
                            }
                        }
                        return last;
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        toast(name + " doesn't seem to be installed on this Fire TV.");
                    }
                });
            }
        };
    }

    /** Returns [name, package] pairs from the TV's installed-app list (best effort). */
    private static List<String[]> fetchApps(LightningClient c) {
        List<String[]> out = new ArrayList<String[]>();
        for (String path : new String[]{"/v1/FireTV/appsV2", "/v1/FireTV/apps"}) {
            try {
                LightningClient.Response r = c.request("GET", path, null, 6000, true);
                if (!r.ok()) continue;
                collectApps(new JSONTokener(r.body).nextValue(), out);
                if (!out.isEmpty()) break;
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        return out;
    }

    private static void collectApps(Object node, List<String[]> out) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectApps(a.opt(i), out);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String pkg = null, name = null;
            for (String k : new String[]{"packageName", "package", "appId", "applicationId", "id"}) {
                String v = o.optString(k, null);
                if (v != null && v.matches("[A-Za-z][\\w]*(\\.[\\w]+)+")) {
                    pkg = v;
                    break;
                }
            }
            for (String k : new String[]{"name", "displayName", "appName", "title", "label"}) {
                String v = o.optString(k, null);
                if (v != null && v.length() > 0 && !v.equals(pkg)) {
                    name = v;
                    break;
                }
            }
            if (pkg != null) {
                out.add(new String[]{name != null ? name : pkg, pkg});
            } else {
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) collectApps(o.opt(keys.next()), out);
            }
        }
    }

    private void showAllApps() {
        final LightningClient c = tv;
        if (c == null || c.token() == null) {
            toast("Connect to your Fire TV first.");
            return;
        }
        toast("Loading apps…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final List<String[]> apps = fetchApps(c);
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (apps.isEmpty()) {
                            toast("Couldn't load the app list from this Fire TV.");
                            return;
                        }
                        String[] names = new String[apps.size()];
                        for (int i = 0; i < names.length; i++) names[i] = apps.get(i)[0];
                        new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle("Open an app")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        launch(apps.get(which)[0], apps.get(which)[1]);
                                    }
                                })
                                .show();
                    }
                });
            }
        });
    }

    // ================================================================== favourites bar

    private static final int MAX_FAVS = 4;
    private LinearLayout favRow;

    /** Known apps: name, colour, package names to try in order. */
    private static final Object[][] PRESETS = {
            {"YouTube", 0xFFE62117, new String[]{"com.amazon.firetv.youtube", "com.google.android.youtube.tv",
                    "com.amazon.firetv.youtube.tv"}},
            {"Stremio", 0xFF7B5BF5, new String[]{"com.stremio.one"}},
            {"Netflix", 0xFFB20710, new String[]{"com.netflix.ninja"}},
            {"Prime Video", 0xFF1A98FF, new String[]{"com.amazon.cloud9", "com.amazon.avod"}},
            {"Disney+", 0xFF113CCF, new String[]{"com.disney.disneyplus"}},
            {"BBC iPlayer", 0xFFE6007E, new String[]{"uk.co.bbc.iplayer", "bbc.iplayer.android"}},
            {"Spotify", 0xFF1DB954, new String[]{"com.spotify.tv.android"}},
            {"Plex", 0xFFE5A00D, new String[]{"com.plexapp.android"}},
            {"Kodi", 0xFF17B2E7, new String[]{"org.xbmc.kodi"}},
            {"Twitch", 0xFF9146FF, new String[]{"tv.twitch.android.viewer", "tv.twitch.android.app"}},
            {"RTÉ Player", 0xFF00A3AD, new String[]{"ie.rte.player", "com.rte.player"}},
            {"ITVX", 0xFF102C3D, new String[]{"air.ITVMobilePlayer"}},
            {"Channel 4", 0xFF6A6A6A, new String[]{"com.channel4.ondemand"}},
            {"NOW", 0xFF00818A, new String[]{"com.bskyb.nowtv.beta"}},
            {"Apple TV", 0xFF3A3A3C, new String[]{"com.apple.atve.amazon.appletv"}},
    };
    private static final int[] PALETTE = {0xFF2E7D6B, 0xFF7A4FB5, 0xFFB5562F, 0xFF2F6DB5, 0xFF9C2F5C, 0xFF5C7A2F};

    private static final class Fav {
        String name;
        String[] pkgs;
        int color;

        Fav(String name, String[] pkgs, int color) {
            this.name = name;
            this.pkgs = pkgs;
            this.color = color;
        }
    }

    private List<Fav> loadFavs() {
        List<Fav> out = new ArrayList<Fav>();
        String raw = prefs.getString("favs", null);
        if (raw == null) {
            out.add(presetFav(0));
            out.add(presetFav(1));
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length() && out.size() < MAX_FAVS; i++) {
                JSONObject o = a.getJSONObject(i);
                JSONArray p = o.getJSONArray("pkgs");
                String[] pkgs = new String[p.length()];
                for (int j = 0; j < pkgs.length; j++) pkgs[j] = p.getString(j);
                out.add(new Fav(o.getString("name"), pkgs, o.getInt("color")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveFavs(List<Fav> favs) {
        JSONArray a = new JSONArray();
        try {
            for (Fav f : favs) {
                JSONObject o = new JSONObject();
                o.put("name", f.name);
                JSONArray p = new JSONArray();
                for (String pkg : f.pkgs) p.put(pkg);
                o.put("pkgs", p);
                o.put("color", f.color);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("favs", a.toString()).apply();
    }

    private static Fav presetFav(int i) {
        return new Fav((String) PRESETS[i][0], (String[]) PRESETS[i][2], (Integer) PRESETS[i][1]);
    }

    /** Colour for an app: its brand colour if known, otherwise a stable pick from the palette. */
    private static int colorFor(String name, String pkg) {
        for (Object[] p : PRESETS) {
            if (((String) p[0]).equalsIgnoreCase(name)) return (Integer) p[1];
            for (String k : (String[]) p[2]) if (k.equals(pkg)) return (Integer) p[1];
        }
        return PALETTE[Math.abs((pkg != null ? pkg : name).hashCode()) % PALETTE.length];
    }

    private void refreshFavourites() {
        favRow.removeAllViews();
        final List<Fav> favs = loadFavs();
        for (int i = 0; i < favs.size(); i++) {
            final int index = i;
            Fav f = favs.get(i);
            RemoteButton b = new RemoteButton(this, 0, f.name, false).labelSize(favs.size() > 3 ? 13 : 15)
                    .colors(f.color, darker(f.color), 0xFFFFFFFF)
                    .describe("Open " + f.name + ". Hold to change.")
                    .onPress(appPress(f.name, f.pkgs))
                    .onLongPress(new RemoteButton.OnPress() {
                        @Override
                        public void onPress() {
                            editFavourite(index);
                        }
                    });
            favRow.addView(b, lp(0, -1, i == 0 ? 0 : 4, 0, 4, 0, 1f));
        }
        if (favs.size() < MAX_FAVS) {
            favRow.addView(new RemoteButton(this, Icons.PLUS, null, false).describe("Add a favourite app")
                    .colors(Theme.SURFACE, Theme.SURFACE_PRESSED, Theme.MUTED)
                    .onPress(new RemoteButton.OnPress() {
                        @Override
                        public void onPress() {
                            pickApp(-1);
                        }
                    }), lp(dpi(52), -1, favs.isEmpty() ? 0 : 4, 0, 4, 0));
        }
        favRow.addView(new RemoteButton(this, Icons.GRID, null, false).describe("All apps")
                .colors(Theme.SURFACE_HIGH, Theme.SURFACE_PRESSED, Theme.TEXT)
                .onPress(new RemoteButton.OnPress() {
                    @Override
                    public void onPress() {
                        showAppsSheet();
                    }
                }), lp(dpi(52), -1, 4, 0, 0, 0));
    }

    private static int darker(int c) {
        int r = (int) (((c >> 16) & 0xff) * 0.78), g = (int) (((c >> 8) & 0xff) * 0.78), b = (int) ((c & 0xff) * 0.78);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void editFavourite(final int index) {
        final List<Fav> favs = loadFavs();
        if (index >= favs.size()) return;
        final List<String> labels = new ArrayList<String>();
        labels.add("Change app");
        if (index > 0) labels.add("Move left");
        if (index < favs.size() - 1) labels.add("Move right");
        labels.add("Remove");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(favs.get(index).name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String choice = labels.get(which);
                        if (choice.equals("Change app")) {
                            pickApp(index);
                            return;
                        }
                        if (choice.equals("Move left")) Collections.swap(favs, index, index - 1);
                        else if (choice.equals("Move right")) Collections.swap(favs, index, index + 1);
                        else favs.remove(index);
                        saveFavs(favs);
                        refreshFavourites();
                    }
                })
                .show();
    }

    /** Chooses an app for slot {@code index} (-1 = add new). Lists the TV's apps when it can, else presets. */
    private void pickApp(final int index) {
        final LightningClient c = tv;
        if (c == null || c.token() == null) {
            showAppChoices(index, new ArrayList<String[]>());
            return;
        }
        toast("Loading apps from your Fire TV…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final List<String[]> apps = fetchApps(c);
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showAppChoices(index, apps);
                    }
                });
            }
        });
    }

    private void showAppChoices(final int index, List<String[]> tvApps) {
        // name, color, packages...
        final List<Fav> choices = new ArrayList<Fav>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String[] a : tvApps) {
            if (seen.add(a[1])) choices.add(new Fav(a[0], new String[]{a[1]}, colorFor(a[0], a[1])));
        }
        if (choices.isEmpty()) { // couldn't read the TV's list: offer the well-known apps
            for (int i = 0; i < PRESETS.length; i++) choices.add(presetFav(i));
        }
        String[] labels = new String[choices.size() + 1];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).name;
        labels[choices.size()] = "Other app (type its package name)…";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(index < 0 ? "Add a favourite" : "Change favourite")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == choices.size()) askPackageName(index);
                        else setFavourite(index, choices.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askPackageName(final int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dpi(20);
        box.setPadding(p, dpi(8), p, 0);
        final EditText name = new EditText(this);
        styleField(name, 16);
        name.setHint("Button name, e.g. Pluto TV");
        box.addView(name, lp(-1, -2));
        final EditText pkg = new EditText(this);
        styleField(pkg, 16);
        pkg.setHint("Package, e.g. tv.pluto.android");
        pkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(pkg, lp(-1, -2, 0, 10, 0, 0));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Other app")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        String k = pkg.getText().toString().trim();
                        if (k.isEmpty()) return;
                        if (n.isEmpty()) n = k.substring(k.lastIndexOf('.') + 1);
                        setFavourite(index, new Fav(n, new String[]{k}, colorFor(n, k)));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setFavourite(int index, Fav f) {
        List<Fav> favs = loadFavs();
        if (f.name.length() > 14) f.name = f.name.substring(0, 13).trim() + "…";
        if (index >= 0 && index < favs.size()) favs.set(index, f);
        else if (favs.size() < MAX_FAVS) favs.add(f);
        saveFavs(favs);
        refreshFavourites();
    }

    // ================================================================== keyboard

    private void showKeyboard() {
        if (tv == null || tv.token() == null) {
            send(null, null); // shows the right "connect first" message
            return;
        }
        final boolean live = adb != null && adb.isConnected();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dpi(20);
        box.setPadding(p, p, p, dpi(8));
        box.addView(text(live
                        ? "Type here — it appears on your TV as you type. Select a search box on the TV first."
                        : "Select a search box on the TV, type here, then tap Send. If nothing appears, "
                        + "set up the volume & typing link (gear menu).",
                13, Theme.MUTED, false));
        final EditText input = new EditText(this);
        styleField(input, 17);
        input.setSingleLine(true);
        input.setImeOptions(live ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_SEND);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(input, lp(-1, -2, 0, 12, 0, 0));

        if (live) {
            input.addTextChangedListener(new TextWatcher() {
                String prev = "";

                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable e) {
                    String now = e.toString();
                    int common = 0;
                    int max = Math.min(prev.length(), now.length());
                    while (common < max && prev.charAt(common) == now.charAt(common)) common++;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < prev.length() - common; i++) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append("input keyevent 67");
                    }
                    String typed = escapeForInputText(now.substring(common));
                    if (typed.length() > 0) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append("input text ").append(typed);
                    }
                    AdbClient a = adb;
                    if (sb.length() > 0 && a != null) a.shell(sb.toString());
                    prev = now;
                }
            });
        }

        LinearLayout keys = row();
        TextView del = pillButton("Delete", false);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AdbClient a = adb;
                if (a != null && a.isConnected()) a.shell("input keyevent 67");
                else key("backspace");
            }
        });
        keys.addView(del, lp(0, dpi(44), 0, 0, 5, 0, 1f));
        final Runnable sendOrEnter = new Runnable() {
            @Override
            public void run() {
                AdbClient a = adb;
                if (live && a != null && a.isConnected()) {
                    a.shell("input keyevent 66");
                } else {
                    sendTextOverPin(input.getText().toString());
                    input.setText("");
                }
            }
        };
        TextView enter = pillButton(live ? "Enter" : "Send", true);
        enter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendOrEnter.run();
            }
        });
        keys.addView(enter, lp(0, dpi(44), 5, 0, 0, 0, 1f));
        box.addView(keys, lp(-1, -2, 0, 12, 0, 0));

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                sendOrEnter.run();
                return true;
            }
        });
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Keyboard")
                .setView(box)
                .setPositiveButton("Done", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        input.requestFocus();
    }

    /** Text entry over the PIN connection (no ADB). The exact format isn't public, so try both known endpoints. */
    private void sendTextOverPin(final String text) {
        if (text.length() == 0) return;
        AdbClient a = adb;
        if (a != null && a.isConnected()) {
            a.shell("input text " + escapeForInputText(text));
            return;
        }
        send(new TvCall() {
            @Override
            public LightningClient.Response run(LightningClient c) throws IOException {
                String body = "{\"text\":" + LightningClient.Json.quote(text) + "}";
                LightningClient.Response r = c.command("POST", "/v1/FireTV/text", body);
                if (!r.ok()) r = c.command("POST", "/v1/FireTV/keyboard", body);
                return r;
            }
        }, new Runnable() {
            @Override
            public void run() {
                toast("This Fire TV doesn't accept typing over the code connection. "
                        + "Set up the volume & typing link from the gear menu.");
            }
        });
    }

    /** Quotes text for "input text": spaces become %s and the whole thing is single-quoted. */
    static String escapeForInputText(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ') out.append("%s");
            else if (ch == '\'') out.append("'\\''");
            else if (ch >= 33 && ch < 127) out.append(ch);
        }
        if (out.length() == 0) return "";
        return "'" + out + "'";
    }

    // ================================================================== typing link (ADB, optional)

    private void updateTypingCard() {
        AdbClient a = adb;
        if (a != null && a.isConnected()) {
            typingStatus.setText("Connected — volume buttons and live typing work.");
            typingStatus.setTextColor(Theme.OK_GREEN);
            typingButton.setText("Connected");
        } else if (adbConnecting) {
            typingStatus.setText(typingStatus.getText());
            typingButton.setText("Connecting…");
        } else {
            typingStatus.setText("Needed for volume and for typing into search boxes. The code connection "
                    + "can't change volume (Amazon's own app can't either). Everything else works without it.");
            typingStatus.setTextColor(Theme.MUTED);
            typingButton.setText("Set up");
        }
    }

    private void showTypingSetup() {
        if (tv == null || tv.token() == null) {
            toast("Pair with your Fire TV first.");
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Set up volume & typing")
                .setMessage("Use this app's remote to do this on the TV:\n\n"
                        + "1. Settings → My Fire TV → Developer options → turn ADB debugging ON.\n"
                        + "   (No Developer options? Open My Fire TV → About and press OK on the device "
                        + "name 7 times.)\n\n"
                        + "2. Come back here and tap Connect.\n\n"
                        + "3. The TV will ask \"Allow USB debugging?\". Use this app's touchpad to tick "
                        + "\"Always allow\", then select OK.\n\n"
                        + "Volume reaches your TV through HDMI-CEC, which is usually on already "
                        + "(Settings → Display & Sounds → HDMI CEC Device Control).")
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        connectTypingLink(true);
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void connectTypingLink(final boolean interactive) {
        final LightningClient t = tv;
        if (t == null || adbConnecting) return;
        adbConnecting = true;
        typingStatus.setText("Connecting…");
        typingStatus.setTextColor(Theme.WARN);
        updateTypingCard();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (adbKeys == null) adbKeys = AdbKeys.loadOrCreate(getFilesDir());
                    AdbClient old = adb;
                    if (old != null) old.close();
                    final AdbClient a = new AdbClient(adbKeys, new AdbClient.Listener() {
                        @Override
                        public void onAuthPromptShown() {
                            onUi(new Runnable() {
                                @Override
                                public void run() {
                                    typingStatus.setText("On the TV: use the touchpad to tick \"Always allow\", "
                                            + "then select OK.");
                                    typingStatus.setTextColor(Theme.WARN);
                                    if (typingDialog.isShowing()) typingDialog.dismiss();
                                    setStatus(ST_BUSY, "On the TV: tick \"Always allow\" and select OK");
                                }
                            });
                        }

                        @Override
                        public void onDisconnected(String reason) {
                            onUi(new Runnable() {
                                @Override
                                public void run() {
                                    updateTypingCard();
                                }
                            });
                        }
                    });
                    a.connect(t.host, 5555);
                    adb = a;
                    prefs.edit().putBoolean("adb_enabled", true).apply();
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            adbConnecting = false;
                            updateTypingCard();
                            if (interactive) toast("Connected — volume and typing now work.");
                            if (tv != null) setConnected(tv.host);
                        }
                    });
                } catch (final Exception e) {
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            adbConnecting = false;
                            updateTypingCard();
                            if (interactive) {
                                String m = e instanceof java.net.ConnectException
                                        ? "The TV refused the connection — check ADB debugging is ON."
                                        : (e.getMessage() != null ? e.getMessage() : "Couldn't connect.");
                                typingStatus.setText(m);
                                typingStatus.setTextColor(Theme.ERROR);
                            }
                        }
                    });
                }
            }
        }, "adb-connect").start();
    }

    // ================================================================== Wi-Fi scan

    private void scanNetwork() {
        final Inet4Address self = NetScanner.localAddress();
        if (self == null) {
            toast("Connect your phone to the same Wi-Fi as the Fire TV first.");
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Searching your Wi-Fi…")
                .setMessage("Looking for Fire TV devices. This takes a few seconds.")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<NetScanner.Found> found;
                try {
                    found = NetScanner.scan(self);
                } catch (InterruptedException e) {
                    return;
                }
                final List<NetScanner.Found> result = found;
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        progress.dismiss();
                        showScanResults(result);
                    }
                });
            }
        }, "scan").start();
    }

    private void showScanResults(final List<NetScanner.Found> found) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        if (found.isEmpty()) {
            b.setTitle("No Fire TV found")
                    .setMessage("Make sure the Fire TV is switched on and your phone is on the same Wi-Fi. "
                            + "You can also type its IP address (on the TV: Settings → My Fire TV → About → Network).")
                    .setPositiveButton("OK", null);
        } else if (found.size() == 1 && found.get(0).confirmed) {
            ipField.setText(found.get(0).ip);
            connect();
            return;
        } else {
            final String[] items = new String[found.size()];
            for (int i = 0; i < items.length; i++) {
                NetScanner.Found f = found.get(i);
                items[i] = f.ip + (f.confirmed ? "   — Fire TV" : "   — other device");
            }
            b.setTitle("Choose your Fire TV").setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    ipField.setText(found.get(which).ip);
                    connect();
                }
            });
        }
        b.show();
    }

    // ================================================================== status & keys

    private boolean statusIsError;

    private void setStatus(int state, String message) {
        int color;
        switch (state) {
            case ST_OK: color = Theme.OK_GREEN; break;
            case ST_BUSY: color = Theme.WARN; break;
            case ST_ERROR: color = Theme.ERROR; break;
            default: color = Theme.MUTED; break;
        }
        statusIsError = state == ST_ERROR;
        ((GradientDrawable) statusDot.getBackground()).setColor(color);
        statusText.setText(message);
        connectBtn.setText(state == ST_OK ? "Reconnect" : "Connect");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        LightningClient c = tv;
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
                && c != null && c.token() != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                volume(code == KeyEvent.KEYCODE_VOLUME_UP);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ================================================================== view helpers

    private void onUi(final Runnable r) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) r.run();
            }
        });
    }

    private int dpi(float v) {
        return Theme.dpi(this, v);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(Theme.SURFACE, 20));
        int p = dpi(16);
        card.setPadding(p, p, p, p);
        return card;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(lp(0, 1, 0, 0, 0, 0, 1f));
        return v;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private void styleField(EditText f, float sp) {
        f.setTextColor(Theme.TEXT);
        f.setHintTextColor(0xFF5D6673);
        f.setTextSize(sp);
        f.setSingleLine(true);
        f.setBackground(rounded(Theme.BG, 12));
        f.setPadding(dpi(14), dpi(12), dpi(14), dpi(12));
    }

    private TextView segment(String label) {
        TextView t = text(label, 14, Theme.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        return t;
    }

    private void styleSegment(TextView t, boolean selected) {
        t.setTextColor(selected ? Theme.ON_ACCENT : Theme.MUTED);
        t.setBackground(selected ? rounded(Theme.ACCENT, 16) : null);
    }

    private TextView pillButton(String label, boolean primary) {
        TextView b = text(label, 15, primary ? Theme.ON_ACCENT : Theme.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(primary ? Theme.ACCENT : Theme.SURFACE_HIGH, 23));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Theme.dp(this, radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dpi(l), dpi(t), dpi(r), dpi(b));
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b, float weight) {
        LinearLayout.LayoutParams p = lp(w, h, l, t, r, b);
        p.weight = weight;
        return p;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
