package com.mycar.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Главный экран: фото машины на весь экран, время, плеер, диагностика, показатели и пять кнопок. */
public class MainActivity extends Activity {

    // Кнопки внизу: 0 музыка, 1 видео, 2 все приложения, 3 навигация, 4 настройки.
    private static final int[] SLOT_IDS = {
            R.id.btn_music, R.id.btn_video, R.id.btn_apps, R.id.btn_nav, R.id.btn_settings};
    private static final int[] ICONS = {
            R.drawable.ic_music, R.drawable.ic_video, R.drawable.ic_apps,
            R.drawable.ic_navigation, R.drawable.ic_settings};
    private static final int[] TITLES = {
            R.string.btn_music, R.string.btn_video, R.string.btn_apps,
            R.string.btn_nav, R.string.btn_settings};

    // Ключ выбранного приложения (null — кнопка не привязана к приложению).
    private static final String[] KEYS = {"music", "video", null, "nav", null};

    // Известные пакеты проверяются первыми.
    private static final String[][] PACKAGES = {
            {"com.spotify.music", "com.google.android.apps.youtube.music", "ru.yandex.music"},
            {"com.google.android.youtube", "org.videolan.vlc",
                    "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro"},
            {},
            {"ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "ru.dublgis.dgismobile",
                    "com.google.android.apps.maps", "com.waze", "com.sygic.aura", "com.navitel"},
            {}
    };

    // Затем ищем по словам в названии приложения.
    private static final String[][] KEYWORDS = {
            {"музыка", "music", "плеер", "player"},
            {"youtube", "ютуб", "видео", "video", "vlc", "кинопоиск"},
            {},
            {"навигатор", "навигация", "navi", "карты", "maps"},
            {}
    };

    private static final String[] ADAPTER_HINTS = {
            "obd", "elm", "vlink", "v-link", "vgate", "icar", "konnwei", "viecar"};

    private static final int REQ_LOCATION = 11;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF6FC3FF;
    private static final int WARN = 0xFFFFB74D;
    private static final int DANGER = 0xFFFF5252;
    private static final int GOOD = 0xFF7BE0A0;
    private static final int MUTED = 0xFF9FB3C8;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();
    private GpsSpeed gps;
    private PlayerHelper player;
    private float u; // «проектный» пиксель: высота экрана / 720
    private int tick;

    private final View[] buttons = new View[5];
    private List<AppUtils.AppEntry> apps = new ArrayList<>();

    private View gSpeed;
    private View gFuel;
    private View gTemp;
    private View gVolt;

    private TextView plTitle;
    private TextView plArtist;
    private ImageView plPlay;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            tick++;
            if (tick % 2 == 0) player.refresh();
            updatePlayer();
            updateGauges();
            updateObdCard();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        u = getResources().getDisplayMetrics().heightPixels / 720f;
        gps = new GpsSpeed(this);
        player = new PlayerHelper(this);
        player.register();

        applyTextSizes();
        buildButtons();
        setupPlayer();
        setupObdCard();
        startObd();
    }

    @Override
    protected void onResume() {
        super.onResume();
        apps = AppUtils.loadApps(getPackageManager(), getPackageName());
        updateButtonSubs();
        if (prefs.getString("obd_mac", null) == null) startObd();
        startGps();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
        gps.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        player.unregister();
    }

    @Override
    public void onBackPressed() {
        // Это главный экран: «Назад» ничего не делает.
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) startGps();
    }

    // ---------- оформление ----------

    private void px(TextView t, float size) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
    }

    private void size(View v, float s) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = Math.round(s * u);
        lp.height = Math.round(s * u);
        v.setLayoutParams(lp);
    }

    private void applyTextSizes() {
        TextView title = findViewById(R.id.title);
        px(title, 20);
        title.setLetterSpacing(0.3f);

        TextView big = findViewById(R.id.clock_big);
        px(big, 72);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setShadowLayer(18f * u, 0f, 0f, 0xFF4FC3F7);

        TextView date = findViewById(R.id.date);
        px(date, 15);
        date.setLetterSpacing(0.12f);

        px((TextView) findViewById(R.id.pl_label), 11);
        px((TextView) findViewById(R.id.pl_title), 19);
        px((TextView) findViewById(R.id.pl_artist), 14);

        px((TextView) findViewById(R.id.obd_title), 12);
        px((TextView) findViewById(R.id.obd_status), 19);
        px((TextView) findViewById(R.id.obd_sub), 14);

        TextView brandSmall = findViewById(R.id.brand_small);
        px(brandSmall, 14);
        brandSmall.setLetterSpacing(0.25f);
        px((TextView) findViewById(R.id.brand_model), 28);

        size(findViewById(R.id.brand_logo), 48);
        View divider = findViewById(R.id.brand_divider);
        ViewGroup.LayoutParams dlp = divider.getLayoutParams();
        dlp.height = Math.round(40 * u);
        divider.setLayoutParams(dlp);

        gSpeed = setupGauge(R.id.g_speed, R.string.gauge_speed);
        gFuel = setupGauge(R.id.g_fuel, R.string.gauge_fuel);
        gTemp = setupGauge(R.id.g_temp, R.string.gauge_temp);
        gVolt = setupGauge(R.id.g_volt, R.string.gauge_volt);
    }

    private View setupGauge(int id, int labelRes) {
        View g = findViewById(id);
        TextView label = g.findViewById(R.id.gauge_label);
        label.setText(labelRes);
        px(label, 14);
        px((TextView) g.findViewById(R.id.gauge_value), 34);
        return g;
    }

    // ---------- нижние кнопки ----------

    private void buildButtons() {
        for (int i = 0; i < SLOT_IDS.length; i++) {
            final int idx = i;
            FrameLayout slot = findViewById(SLOT_IDS[i]);
            View b = LayoutInflater.from(this).inflate(R.layout.item_btn, slot, false);

            ImageView icon = b.findViewById(R.id.btn_icon);
            icon.setImageResource(ICONS[i]);
            size(icon, 46);

            TextView title = b.findViewById(R.id.btn_title);
            title.setText(TITLES[i]);
            px(title, 20);
            title.setLetterSpacing(0.08f);

            TextView sub = b.findViewById(R.id.btn_sub);
            px(sub, 14);

            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onButton(idx);
                }
            });
            if (KEYS[i] != null) {
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        pickApp(idx);
                        return true;
                    }
                });
            }
            slot.addView(b);
            buttons[i] = b;
        }
    }

    private void onButton(int idx) {
        if (idx == 2) {
            startActivity(new Intent(this, AppsActivity.class));
        } else if (idx == 4) {
            openSettingsMenu();
        } else {
            String pkg = currentPackage(idx);
            if (pkg == null) {
                pickApp(idx);
            } else if (!AppUtils.launch(this, pkg)) {
                Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
                pickApp(idx);
            }
        }
    }

    /** Выбранное пользователем приложение, а если не выбрано — угаданное. */
    private String currentPackage(int idx) {
        String saved = prefs.getString(KEYS[idx], null);
        if (saved != null) {
            for (AppUtils.AppEntry a : apps) {
                if (a.pkg.equals(saved)) return saved;
            }
        }
        return AppUtils.guess(apps, PACKAGES[idx], KEYWORDS[idx]);
    }

    private String labelOf(String pkg) {
        for (AppUtils.AppEntry a : apps) {
            if (a.pkg.equals(pkg)) return a.label;
        }
        return null;
    }

    private void updateButtonSubs() {
        for (int i = 0; i < buttons.length; i++) {
            TextView sub = buttons[i].findViewById(R.id.btn_sub);
            if (i == 2) {
                sub.setText(R.string.sub_apps);
            } else if (i == 4) {
                sub.setText(R.string.sub_settings);
            } else {
                String pkg = currentPackage(i);
                String label = pkg == null ? null : labelOf(pkg);
                if (label != null) {
                    sub.setText(label);
                } else {
                    sub.setText(R.string.sub_choose);
                }
            }
        }
    }

    private void pickApp(final int idx) {
        if (KEYS[idx] == null) return;
        final List<AppUtils.AppEntry> list = apps;
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = list.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(TITLES[idx])
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString(KEYS[idx], list.get(which).pkg).apply();
                        updateButtonSubs();
                    }
                })
                .setNegativeButton(R.string.reset, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().remove(KEYS[idx]).apply();
                        updateButtonSubs();
                    }
                })
                .show();
    }

    // ---------- плеер ----------

    private void setupPlayer() {
        plTitle = findViewById(R.id.pl_title);
        plArtist = findViewById(R.id.pl_artist);
        plPlay = findViewById(R.id.pl_play);

        int pad = Math.round(7 * u);
        int[] ids = {R.id.pl_prev, R.id.pl_play, R.id.pl_next};
        for (int id : ids) {
            ImageView v = findViewById(id);
            size(v, 42);
            v.setPadding(pad, pad, pad, pad);
        }

        findViewById(R.id.pl_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.previous();
            }
        });
        findViewById(R.id.pl_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.playPause();
            }
        });
        findViewById(R.id.pl_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.next();
            }
        });
        findViewById(R.id.card_player).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayerCard();
            }
        });
    }

    private void onPlayerCard() {
        if (!player.hasAccess && !player.hasData()) {
            openNotificationAccess();
        } else {
            String pkg = currentPackage(0);
            if (pkg != null) AppUtils.launch(this, pkg);
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            Toast.makeText(this, R.string.notif_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void updatePlayer() {
        String t = player.title;
        if (t != null && !t.isEmpty()) {
            plTitle.setText(t);
            String a = player.artist;
            plArtist.setText(a == null ? "" : a);
        } else {
            plTitle.setText(R.string.pl_idle);
            plArtist.setText(player.hasAccess ? "" : getString(R.string.pl_hint));
        }
        plPlay.setImageResource(player.playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ---------- настройки ----------

    private void openSettingsMenu() {
        String[] items = {
                getString(R.string.set_obd),
                getString(R.string.set_bt),
                getString(R.string.set_notif),
                getString(R.string.set_android)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_settings)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            pickObdAdapter();
                        } else if (which == 1) {
                            startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                        } else if (which == 2) {
                            openNotificationAccess();
                        } else {
                            startSafe(new Intent(Settings.ACTION_SETTINGS));
                        }
                    }
                })
                .show();
    }

    private void startSafe(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- OBD-адаптер ----------

    private void setupObdCard() {
        px((TextView) findViewById(R.id.obd_title), 12);
        View card = findViewById(R.id.card_obd);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (prefs.getString("obd_mac", null) == null) {
                    pickObdAdapter();
                } else {
                    startActivity(new Intent(MainActivity.this, DiagActivity.class));
                }
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                pickObdAdapter();
                return true;
            }
        });
    }

    private void startObd() {
        String mac = prefs.getString("obd_mac", null);
        if (mac == null) mac = autoPickAdapter();
        if (mac != null) ObdClient.get().start(mac);
    }

    /** Если среди подключённых по Bluetooth устройств есть адаптер ELM327 — выбираем сами. */
    private String autoPickAdapter() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return null;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return null;
            for (BluetoothDevice d : bonded) {
                String name = d.getName();
                if (name == null) continue;
                String n = name.toLowerCase(Locale.ROOT);
                for (String hint : ADAPTER_HINTS) {
                    if (n.contains(hint)) {
                        saveAdapter(d);
                        return d.getAddress();
                    }
                }
            }
        } catch (SecurityException ignored) {
            // нет разрешения на Bluetooth
        }
        return null;
    }

    private void saveAdapter(BluetoothDevice d) {
        String name = d.getName();
        prefs.edit()
                .putString("obd_mac", d.getAddress())
                .putString("obd_name", name == null ? d.getAddress() : name)
                .apply();
    }

    private void pickObdAdapter() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, R.string.no_bluetooth, Toast.LENGTH_SHORT).show();
            return;
        }

        final List<BluetoothDevice> devices = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) devices.addAll(bonded);
        } catch (SecurityException ignored) {
            // нет разрешения на Bluetooth
        }

        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.obd_pick_title)
                    .setMessage(R.string.obd_pair_first)
                    .setPositiveButton(R.string.open_bt_settings, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                        }
                    })
                    .setNegativeButton(R.string.diag_cancel, null)
                    .show();
            return;
        }

        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) {
            String n = devices.get(i).getName();
            names[i] = (n == null ? "?" : n) + "  " + devices.get(i).getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.obd_pick_title)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        BluetoothDevice d = devices.get(which);
                        saveAdapter(d);
                        ObdClient.get().start(d.getAddress());
                    }
                })
                .show();
    }

    // ---------- GPS ----------

    private void startGps() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("asked_location", false)) {
                prefs.edit().putBoolean("asked_location", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            }
            return;
        }
        gps.start();
    }

    // ---------- обновление экрана ----------

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private void showGauge(View g, String number, String unit, int progress, int color) {
        TextView value = g.findViewById(R.id.gauge_value);
        if (unit.isEmpty()) {
            value.setText(number);
        } else {
            SpannableString s = new SpannableString(number + " " + unit);
            s.setSpan(new RelativeSizeSpan(0.5f), number.length(), s.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            value.setText(s);
        }
        value.setTextColor(color == ACCENT ? WHITE : color);
        ProgressBar bar = g.findViewById(R.id.gauge_bar);
        bar.setProgress(progress);
        bar.setProgressTintList(ColorStateList.valueOf(color));
    }

    private void updateGauges() {
        ObdClient obd = ObdClient.get();
        final String none = "\u2014";

        int spd = obd.speed;
        if (spd == ObdClient.UNKNOWN && gps.hasFix() && gps.speedKmh() >= 0) {
            spd = gps.speedKmh();
        }
        if (spd != ObdClient.UNKNOWN) {
            showGauge(gSpeed, String.valueOf(spd), getString(R.string.unit_speed),
                    clamp(spd * 100 / 200), ACCENT);
        } else {
            showGauge(gSpeed, none, "", 0, ACCENT);
        }

        int fuel = obd.fuel;
        if (fuel != ObdClient.UNKNOWN) {
            showGauge(gFuel, String.valueOf(fuel), getString(R.string.unit_fuel), clamp(fuel), ACCENT);
        } else {
            showGauge(gFuel, none, "", 0, ACCENT);
        }

        int temp = obd.coolant;
        if (temp != ObdClient.UNKNOWN) {
            int color = temp >= 110 ? DANGER : (temp >= 100 ? WARN : ACCENT);
            showGauge(gTemp, String.valueOf(temp), getString(R.string.unit_temp),
                    clamp((temp - 40) * 100 / 80), color);
        } else {
            showGauge(gTemp, none, "", 0, ACCENT);
        }

        float volt = obd.voltage;
        if (volt > 0f) {
            int color = (volt < 11.8f || volt > 15.2f) ? DANGER
                    : ((volt < 12.2f || volt > 14.8f) ? WARN : ACCENT);
            showGauge(gVolt, String.format(Locale.US, "%.1f", volt), getString(R.string.unit_volt),
                    clamp(Math.round((volt - 11f) * 100f / 4f)), color);
        } else {
            showGauge(gVolt, none, "", 0, ACCENT);
        }
    }

    private void updateObdCard() {
        ObdClient obd = ObdClient.get();
        String mac = prefs.getString("obd_mac", null);
        String name = prefs.getString("obd_name", null);
        TextView status = findViewById(R.id.obd_status);
        TextView sub = findViewById(R.id.obd_sub);

        if (mac == null) {
            status.setText(R.string.obd_none);
            status.setTextColor(WHITE);
            sub.setText(R.string.obd_tap_choose);
            sub.setTextColor(MUTED);
            return;
        }

        switch (obd.state) {
            case ObdClient.CONNECTED:
                status.setText(R.string.obd_connected);
                status.setTextColor(GOOD);
                break;
            case ObdClient.BT_OFF:
                status.setText(R.string.obd_bt_off);
                status.setTextColor(DANGER);
                break;
            case ObdClient.RETRY:
                status.setText(R.string.obd_retry);
                status.setTextColor(WARN);
                break;
            default:
                status.setText(R.string.obd_connecting);
                status.setTextColor(WARN);
                break;
        }

        if (obd.state == ObdClient.CONNECTED) {
            int n = obd.dtcCount;
            if (n < 0) {
                sub.setText(R.string.obd_errors_checking);
                sub.setTextColor(MUTED);
            } else if (n == 0) {
                sub.setText(R.string.obd_errors_none);
                sub.setTextColor(GOOD);
            } else {
                sub.setText(getString(R.string.obd_errors_count, n));
                sub.setTextColor(WARN);
            }
        } else {
            sub.setText(name != null ? name : mac);
            sub.setTextColor(MUTED);
        }
    }
}
