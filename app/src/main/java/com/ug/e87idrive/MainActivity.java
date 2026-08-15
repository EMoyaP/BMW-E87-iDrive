package com.ug.e87idrive;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@SuppressLint("SetTextI18n") // This fixed Spanish automotive UI is intentionally assembled in code.
public class MainActivity extends Activity {
    private static final int REQUEST_USB_DIAGNOSTIC_DIRECTORY = 301;
    private int BG, PANEL, PANEL2, ACCENT, BLUE, TEXT, MUTED, LINE;
    private SharedPreferences vehiclePreferences, uiPreferences;
    private AppRepository apps;
    private LinearLayout root, vehicleRows, statusBar;
    private LinearLayout fuelRows;
    private GridLayout quickGrid;
    private TextView clock, date, mediaTitle, mediaArtist, mediaSource, mediaState, fuelHeading, fuelFooter;
    private TextView phoneDevice, phoneConnection;
    private TextView radioBand, radioStation, radioDetail;
    private ImageView mediaArtwork;
    private SpeedGaugeView speedGauge;
    private final java.util.Map<String, TextView> roleLabels = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, TextView> roleHints = new java.util.LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GpsSpeedProvider gps;
    private DiagnosticEngine diagnostics;
    private VehicleDataRepository vehicleData;
    private MediaSessionProvider media;
    private FuelStationProvider fuelStations;
    private BluetoothDeviceProvider bluetoothState;
    private UsbDiagnosticRecorder usbDiagnostics;
    private UsbDebugWizardDialog activeUsbWizard;
    private String lastCorrelationReport = "";
    private boolean foreground;
    private boolean usbCaptureRunning;
    private boolean startUsbCaptureAfterPicker;
    private int mediaRefreshTick;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        hideUi();
        vehiclePreferences = getSharedPreferences("vehicle", MODE_PRIVATE);
        uiPreferences = getSharedPreferences("ui", MODE_PRIVATE);
        apps = new AppRepository(this);
        diagnostics = new DiagnosticEngine(this);
        usbDiagnostics = new UsbDiagnosticRecorder(this);
        fuelStations = new FuelStationProvider(this, this::refreshFuelWidget);
        bluetoothState = new BluetoothDeviceProvider(this, this::refreshPhoneWidget);
        gps = new GpsSpeedProvider(this, (location, kmh) -> runOnUiThread(() -> {
            refreshVehicle();
            fuelStations.onLocation(location);
        }));
        vehicleData = new VehicleDataRepository(this, gps, diagnostics, () -> {
            refreshVehicle();
            refreshStatus();
        });
        media = new MediaSessionProvider(this);
        applyPalette();
        buildUi();
        startClock();
        requestLocationIfNeeded();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        hideUi();
        autoDetectApps();
        refreshAll();
        bluetoothState.start();
        fuelStations.start(gps.getLastLocation());
        vehicleData.start();
        diagnostics.startPassiveProbe();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideUi();
    }

    @Override protected void onPause() {
        foreground = false;
        bluetoothState.stop();
        fuelStations.stop();
        if (!usbCaptureRunning) {
            diagnostics.stopPassiveProbe();
            vehicleData.stop();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (activeUsbWizard != null && activeUsbWizard.isRunning()) activeUsbWizard.finishFromHost();
        handler.removeCallbacksAndMessages(null);
        diagnostics.stopPassiveProbe();
        bluetoothState.stop();
        fuelStations.close();
        vehicleData.stop();
        if (usbDiagnostics != null) usbDiagnostics.close();
        super.onDestroy();
    }

    private void buildUi() {
        roleLabels.clear();
        roleHints.clear();
        root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(0, 0, 0, 0);
        setContentView(root);
        root.addView(header(), lp(-1, dp(74)));
        LinearLayout content = vertical();
        content.setPadding(dp(10), dp(10), dp(10), 0);
        root.addView(content, lp(-1, 0, 1));
        LinearLayout upper = horizontal();
        upper.addView(sideMenu(), lp(dp(235), -1));
        upper.addView(dashboardUpper(), lp(0, -1, 1));
        content.addView(upper, lp(-1, 0, 1));
        content.addView(dashboardLower(), lp(-1, dp(190)));
        statusBar = horizontal();
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setBackground(statusBg());
        statusBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        statusBar.setOnClickListener(v -> diagnosticModal());
        LinearLayout.LayoutParams statusParams = lp(-1, dp(60));
        statusParams.setMargins(dp(10), dp(3), dp(10), dp(7));
        root.addView(statusBar, statusParams);
    }

    private View header() {
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = horizontal();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        HeaderBrandView logo = new HeaderBrandView(this);
        logo.setContentDescription("BMW iDrive");
        brand.addView(logo, lp(-1, -1));
        bar.setBackground(headerBg());
        bar.addView(brand, lp(dp(460), -1));
        LinearLayout middle = vertical();
        middle.setGravity(Gravity.CENTER);
        clock = txt("--:--", 34, TEXT, true);
        date = txt("", 13, MUTED, false);
        clock.setGravity(Gravity.CENTER);
        date.setGravity(Gravity.CENTER);
        middle.addView(clock);
        middle.addView(date);
        bar.addView(middle, lp(0, -1, 1));
        View oemStatusArea = new View(this);
        oemStatusArea.setContentDescription("Zona reservada para la barra OEM de la unidad");
        bar.addView(oemStatusArea, lp(dp(460), -1));
        return bar;
    }

    private View sideMenu() {
        LinearLayout side = vertical();
        side.setPadding(dp(4), dp(4), dp(10), dp(4));
        side.setBackground(sidePanelBg());
        addMenu(side, "Multimedia", "media", R.drawable.ic_menu_media, true);
        addMenu(side, "Radio", "radio", R.drawable.ic_menu_radio, false);
        addMenu(side, "Navegación", "nav", R.drawable.ic_menu_navigation, false);
        addMenu(side, "Android Auto", "auto", R.drawable.ic_menu_auto, false);
        addMenu(side, "Teléfono", "phone", R.drawable.ic_menu_phone, false);
        View applicationMenu = menuItem("Aplicaciones", R.drawable.ic_menu_apps, false);
        applicationMenu.setOnClickListener(v -> quickAppsModal());
        side.addView(applicationMenu, lp(-1, 0, 1));
        View settings = menuItem("Ajustes", R.drawable.ic_menu_settings, false);
        settings.setOnClickListener(v -> settingsModal());
        side.addView(settings, lp(-1, 0, 1));
        return side;
    }

    private View dashboardUpper() {
        LinearLayout upper = horizontal();

        FrameLayout car = new FrameLayout(this);
        car.setPadding(dp(8), 0, dp(8), 0);
        car.setBackground(carPanelBg());
        ImageView carImage = new ImageView(this);
        carImage.setImageBitmap(decodeSampledResource(R.drawable.bmw_e87_hero_v2, 640, 400));
        carImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        carImage.setContentDescription("BMW Serie 1 E87 azul marino");
        car.addView(carImage, frameLp(-1, -1, Gravity.CENTER));

        LinearLayout vehicleTitle = vertical();
        vehicleTitle.setPadding(dp(22), dp(18), 0, 0);
        LinearLayout titleLine = horizontal();
        titleLine.addView(txt("BMW ", 26, TEXT, true));
        titleLine.addView(txt("SERIE 1 E87", 26, Color.rgb(52, 103, 166), false));
        vehicleTitle.addView(titleLine);
        vehicleTitle.addView(txt("OEM STYLE", 13, MUTED, false));
        car.addView(vehicleTitle, frameLp(-2, -2, Gravity.TOP | Gravity.START));

        TextView pages = txt("━  ━  ━  ━", 16, ACCENT, true);
        pages.setGravity(Gravity.CENTER);
        car.addView(pages, frameLp(dp(150), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        upper.addView(car, lp(0, -1, 1.45f));

        LinearLayout right = vertical();
        right.setPadding(dp(8), 0, 0, 0);
        right.addView(mediaWidget(), lp(-1, 0, 1));
        right.addView(vehicleWidget(), lp(-1, 0, 1.15f));
        upper.addView(right, lp(0, -1, 1));
        return upper;
    }

    private View dashboardLower() {
        LinearLayout lower = horizontal();
        lower.setPadding(0, dp(8), 0, 0);
        addLowerCard(lower, fuelWidget(), 1.15f, true, false);
        addLowerCard(lower, mainCard("RADIO", "radio"), 1, false, false);
        addLowerCard(lower, mainCard("ANDROID AUTO", "auto"), .82f, false, false);
        addLowerCard(lower, mainCard("TELÉFONO / BLUETOOTH", "phone"), .88f, false, false);
        quickGrid = new GridLayout(this);
        quickGrid.setColumnCount(3);
        quickGrid.setRowCount(2);
        quickGrid.setBackground(cardBg());
        quickGrid.setPadding(dp(5), dp(5), dp(5), dp(5));
        addLowerCard(lower, quickGrid, 1.32f, false, true);
        return lower;
    }

    private View fuelWidget() {
        LinearLayout box = card();
        box.setPadding(dp(8), dp(6), dp(8), dp(5));
        fuelHeading = txt("GASOLINERAS", 12, BLUE, true);
        fuelHeading.setGravity(Gravity.CENTER);
        fuelHeading.setOnClickListener(v -> fuelSettingsModal());
        box.addView(fuelHeading, lp(-1, dp(18)));
        fuelRows = vertical();
        fuelRows.setGravity(Gravity.CENTER);
        box.addView(fuelRows, lp(-1, 0, 1));
        fuelFooter = txt("GPS · DATOS OFICIALES", 8, MUTED, false);
        fuelFooter.setGravity(Gravity.CENTER);
        fuelFooter.setMaxLines(1);
        fuelFooter.setContentDescription("Tocar para actualizar los precios de gasolineras");
        fuelFooter.setOnClickListener(v -> {
            fuelStations.forceRefresh();
            toast("Actualizando precios cuando haya conexión");
        });
        box.addView(fuelFooter, lp(-1, dp(14)));
        box.setOnLongClickListener(v -> { fuelSettingsModal(); return true; });
        refreshFuelWidget(fuelStations.getSnapshot());
        return box;
    }

    private void refreshFuelWidget(FuelStationProvider.Snapshot snapshot) {
        if (snapshot == null || fuelRows == null) return;
        Locale spanish = new Locale("es", "ES");
        fuelHeading.setText(String.format(spanish, "GASOLINERAS · %s",
                snapshot.fuelLabel.toUpperCase(spanish)));
        fuelRows.removeAllViews();
        if (snapshot.cheapest == null || snapshot.nearest == null) {
            TextView state = txt(snapshot.message == null || snapshot.message.isEmpty()
                    ? "Consultando precios…" : snapshot.message, 11, snapshot.loading ? BLUE : MUTED, false);
            state.setGravity(Gravity.CENTER);
            state.setMaxLines(2);
            fuelRows.addView(state, lp(-1, 0, 1));
            TextView configure = txt("Mantén pulsado para configurar", 8, MUTED, false);
            configure.setGravity(Gravity.CENTER);
            fuelRows.addView(configure, lp(-1, dp(18)));
        } else {
            fuelRows.addView(fuelStationRow("MÁS BARATA", snapshot.cheapest, ACCENT), lp(-1, 0, 1));
            fuelRows.addView(fuelStationRow("MÁS CERCANA", snapshot.nearest, BLUE), lp(-1, 0, 1));
        }
        String freshness = snapshot.cached ? "CACHÉ" : "MITECO";
        String dateText = compactDatasetDate(snapshot.datasetDate);
        fuelFooter.setText(String.format(spanish, "%d KM · %s%s",
                snapshot.radiusKm, freshness, dateText));
    }

    private String compactDatasetDate(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String cleaned = value.trim();
        String[] pieces = cleaned.split("\\s+");
        if (pieces.length >= 2) return " · " + pieces[0] + " " + pieces[1].substring(0, Math.min(5, pieces[1].length()));
        return " · " + cleaned;
    }

    private View fuelStationRow(String kind, FuelStationProvider.Station station, int tone) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(2), dp(6), dp(2));
        row.setBackground(fuelRowBg(tone));
        TextView badge = txt("MÁS BARATA".equals(kind) ? "€" : "⌖", 18, tone, true);
        badge.setGravity(Gravity.CENTER);
        row.addView(badge, lp(dp(28), -1));
        LinearLayout copy = vertical();
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(5), 0, 0, 0);
        LinearLayout top = horizontal();
        TextView label = txt(kind, 8, tone, true);
        TextView price = txt(String.format(new Locale("es", "ES"), "%.3f €/L", station.price), 11, TEXT, true);
        price.setGravity(Gravity.END);
        top.addView(label, lp(0, -1, 1));
        top.addView(price, lp(dp(82), -1));
        TextView name = txt(station.brand + " · "
                + String.format(new Locale("es", "ES"), "%.1f km", station.distanceKm), 10, TEXT, false);
        name.setMaxLines(1);
        copy.addView(top, lp(-1, dp(17)));
        copy.addView(name, lp(-1, dp(19)));
        row.addView(copy, lp(0, -1, 1));
        row.setContentDescription(kind + ": " + station.brand + ", " + station.price
                + " euros por litro, " + station.distanceKm + " kilómetros");
        row.setOnClickListener(v -> openStationInMaps(station));
        return row;
    }

    private void openStationInMaps(FuelStationProvider.Station station) {
        String coordinates = String.format(Locale.ROOT, "%.7f,%.7f", station.latitude, station.longitude);
        Intent google = new Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + Uri.encode(coordinates) + "&mode=d"));
        google.setPackage("com.google.android.apps.maps");
        try {
            startActivity(google);
            return;
        } catch (Exception ignored) {}
        Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="
                + Uri.encode(coordinates + "(" + station.brand + ")")));
        try { startActivity(fallback); }
        catch (Exception ignored) { toast("No hay una aplicación de mapas disponible"); }
    }

    private void addLowerCard(LinearLayout row, View card, float weight, boolean first, boolean last) {
        LinearLayout.LayoutParams params = lp(0, -1, weight);
        params.setMargins(first ? 0 : dp(3), 0, last ? 0 : dp(3), 0);
        row.addView(card, params);
    }

    private View mediaWidget() {
        LinearLayout box = card();
        box.addView(txt("MULTIMEDIA", 13, BLUE, true));
        LinearLayout content = horizontal();
        content.setGravity(Gravity.CENTER_VERTICAL);
        mediaArtwork = new ImageView(this);
        mediaArtwork.setImageResource(R.drawable.ic_menu_media);
        mediaArtwork.setColorFilter(Color.rgb(33, 68, 105));
        mediaArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArtwork.setPadding(dp(10), dp(10), dp(10), dp(10));
        mediaArtwork.setBackground(slotBg());
        content.addView(mediaArtwork, lp(dp(92), dp(92)));
        LinearLayout details = vertical();
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(dp(14), 0, 0, 0);
        mediaTitle = txt("Acceso multimedia pendiente", 17, TEXT, false);
        mediaTitle.setMaxLines(2);
        mediaArtist = txt("Actívalo en Ajustes para leer carátula y título", 13, MUTED, false);
        mediaArtist.setMaxLines(2);
        mediaSource = txt("MediaSession estándar", 10, MUTED, false);
        details.addView(mediaTitle);
        details.addView(mediaArtist);
        details.addView(mediaSource);
        content.addView(details, lp(0, -1, 1));
        box.addView(content, lp(-1, 0, 1));
        LinearLayout footer = horizontal();
        mediaState = txt("", 10, BLUE, true);
        footer.addView(mediaState, lp(0, dp(30), 1));
        TextView controls = txt("◀       ▶       ▶|", 17, TEXT, false);
        controls.setGravity(Gravity.CENTER);
        footer.addView(controls, lp(dp(190), dp(30)));
        box.addView(footer);
        TextView hint = txt("Tocar: abrir app · mantener: asignar", 9, MUTED, false);
        box.addView(hint);
        box.setOnClickListener(v -> launchRole("media"));
        box.setOnLongClickListener(v -> { appPicker("media", "Multimedia"); return true; });
        return box;
    }

    private void refreshMedia() {
        if (mediaTitle == null) return;
        MediaSessionProvider.Snapshot snapshot = media.refresh();
        mediaTitle.setText(snapshot.title);
        mediaArtist.setText(snapshot.artist);
        mediaSource.setText(snapshot.source);
        mediaState.setText(snapshot.sessionAvailable ? snapshot.state : "LECTURA PASIVA");
        if (snapshot.artwork != null) {
            mediaArtwork.setColorFilter(null);
            mediaArtwork.setPadding(0, 0, 0, 0);
            mediaArtwork.setImageBitmap(snapshot.artwork);
        } else {
            mediaArtwork.setImageResource(R.drawable.ic_menu_media);
            mediaArtwork.setColorFilter(Color.rgb(33, 68, 105));
            mediaArtwork.setPadding(dp(10), dp(10), dp(10), dp(10));
        }
    }

    private View vehicleWidget() {
        LinearLayout box = card();
        LinearLayout title = horizontal();
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(txt("ORDENADOR DE A BORDO", 13, BLUE, true), lp(0, -1, 1));
        title.setOnClickListener(v -> vehicleModal());
        box.addView(title, lp(-1, dp(34)));
        LinearLayout content = horizontal();
        vehicleRows = vertical();
        content.addView(vehicleRows, lp(0, -1, 1.5f));
        speedGauge = new SpeedGaugeView(this);
        content.addView(speedGauge, lp(0, -1, .9f));
        box.addView(content, lp(-1, 0, 1));
        box.setOnLongClickListener(v -> { vehicleModal(); return true; });
        return box;
    }

    private View mainCard(String title, String role) {
        LinearLayout box = card();
        box.setGravity(Gravity.CENTER);
        TextView heading = txt(title, 12, BLUE, true);
        heading.setGravity(Gravity.CENTER);
        TextView value = txt("Sin configurar", 15, TEXT, false);
        value.setGravity(Gravity.CENTER);
        value.setMaxLines(2);
        if (!"phone".equals(role)) roleLabels.put(role, value);
        TextView hint = txt("Mantener para asignar", 9, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        roleHints.put(role, hint);
        box.addView(heading);

        if ("nav".equals(role)) {
            NavigationPreviewView map = new NavigationPreviewView(this);
            box.addView(map, lp(-1, 0, .82f));
            box.addView(value, lp(-1, 0, .24f));
        } else if ("radio".equals(role)) {
            LinearLayout tuner = vertical();
            tuner.setGravity(Gravity.CENTER);
            radioBand = txt("RADIO", 12, MUTED, true);
            radioBand.setGravity(Gravity.START);
            radioStation = txt("—", 28, TEXT, false);
            radioStation.setGravity(Gravity.CENTER);
            radioStation.setMaxLines(2);
            tuner.addView(radioBand, lp(-1, dp(24)));
            tuner.addView(radioStation, lp(-1, 0, 1));
            box.addView(tuner, lp(-1, 0, .72f));
            radioDetail = value;
            box.addView(value, lp(-1, 0, .34f));
        } else if ("phone".equals(role)) {
            phoneDevice = value;
            LinearLayout phone = horizontal();
            phone.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout details = vertical();
            details.setGravity(Gravity.CENTER);
            details.addView(value);
            phoneConnection = txt("Comprobando Bluetooth…", 10, MUTED, false);
            phoneConnection.setGravity(Gravity.CENTER);
            phoneConnection.setMaxLines(2);
            details.addView(phoneConnection);
            phone.addView(details, lp(0, -1, 1));
            ImageView bluetooth = new ImageView(this);
            bluetooth.setImageResource(R.drawable.ic_role_bluetooth);
            bluetooth.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            phone.addView(bluetooth, lp(dp(72), -1));
            box.addView(phone, lp(-1, 0, 1));
        } else {
            ImageView androidAuto = new ImageView(this);
            androidAuto.setImageResource(R.drawable.ic_role_android_auto);
            androidAuto.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            androidAuto.setPadding(dp(8), dp(5), dp(8), 0);
            box.addView(androidAuto, lp(-1, 0, .75f));
            box.addView(value, lp(-1, 0, .32f));
        }
        box.addView(hint);
        box.setOnClickListener(v -> launchRole(role));
        box.setOnLongClickListener(v -> { appPicker(role, title); return true; });
        return box;
    }

    private void refreshAll() {
        refreshQuick();
        refreshVehicle();
        refreshStatus();
        refreshMedia();
        refreshPhoneWidget(bluetoothState.getState());
        for (String role : AppRepository.ROLES) {
            TextView text = roleLabels.get(role);
            if (text != null) text.setText(roleDisplay(role));
            TextView hint = roleHints.get(role);
            if (hint != null) {
                if ("phone".equals(role)) {
                    hint.setText(apps.getPackage(role) == null
                            ? "Mantener para asignar teléfono"
                            : "Tocar para abrir teléfono · mantener para cambiar");
                } else if ("radio".equals(role)) {
                    hint.setText(apps.getPackage(role) == null ? "Mantener para asignar radio"
                            : "Tocar para abrir radio · mantener para cambiar");
                } else {
                    hint.setText(apps.getPackage(role) == null ? "Mantener para asignar"
                            : "Tocar para abrir · mantener para cambiar");
                }
            }
        }
        refreshRadioWidget();
    }

    private void refreshRadioWidget() {
        if (radioBand == null || radioStation == null || radioDetail == null) return;
        String packageName = apps.getPackage("radio");
        if (packageName == null) {
            radioBand.setText("RADIO");
            radioStation.setText("—");
            radioStation.setTextColor(TEXT);
            radioDetail.setText("Sin configurar");
            return;
        }
        MediaSessionProvider.Snapshot snapshot = media.refreshForPackage(packageName);
        if (!snapshot.sessionAvailable) {
            radioBand.setText("RADIO");
            radioStation.setText("—");
            radioStation.setTextColor(TEXT);
            radioDetail.setText(snapshot.accessGranted
                    ? apps.label(packageName) + "\nEmisora no expuesta"
                    : "Permite acceso multimedia");
            return;
        }
        radioBand.setText(radioBand(snapshot.title + " " + snapshot.artist));
        radioStation.setText(snapshot.title);
        radioStation.setTextColor(BLUE);
        radioDetail.setText(snapshot.artist);
    }

    private String radioBand(String publishedText) {
        String normalized = publishedText == null ? "" : publishedText.toUpperCase(Locale.ROOT);
        if (normalized.contains("DAB")) return "DAB";
        if (normalized.contains(" FM") || normalized.startsWith("FM") || normalized.contains("MHZ")) return "FM";
        if (normalized.contains(" AM") || normalized.startsWith("AM") || normalized.contains("KHZ")) return "AM";
        return "RADIO · EN DIRECTO";
    }

    private void refreshPhoneWidget(BluetoothDeviceProvider.State state) {
        if (state == null || phoneDevice == null || phoneConnection == null) return;
        phoneDevice.setText(state.terminalName);
        phoneDevice.setTextColor(state.connected ? BLUE : TEXT);
        phoneConnection.setText(state.detail);
        phoneConnection.setTextColor(state.connected ? BLUE : MUTED);
    }

    private String roleDisplay(String role) {
        String pkg = apps.getPackage(role);
        return pkg == null ? "Sin configurar" : apps.label(pkg);
    }

    private void refreshQuick() {
        if (quickGrid == null) return;
        quickGrid.removeAllViews();
        for (int i = 0; i < 6; i++) {
            String key = "quick_" + i;
            String pkg = apps.getPackage(key);
            LinearLayout slot = vertical();
            slot.setGravity(Gravity.CENTER);
            slot.setBackground(slotBg());
            View icon;
            if (pkg == null || apps.icon(pkg) == null) {
                TextView plus = txt(pkg == null ? "+" : "▣", pkg == null ? 27 : 20, pkg == null ? ACCENT : BLUE, true);
                plus.setGravity(Gravity.CENTER);
                icon = plus;
            } else {
                ImageView appIcon = new ImageView(this);
                appIcon.setImageDrawable(apps.icon(pkg));
                appIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                appIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
                icon = appIcon;
            }
            TextView label = txt(pkg == null ? "Añadir" : apps.label(pkg), 9, TEXT, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            slot.addView(icon, lp(-1, 0, 1));
            slot.addView(label, lp(-1, dp(26)));
            if (pkg == null) slot.setOnClickListener(v -> appPicker(key, "Acceso rápido"));
            else slot.setOnClickListener(v -> launchKey(key));
            slot.setOnLongClickListener(v -> { quickOptions(key, apps.getPackage(key)); return true; });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = 0;
            params.columnSpec = GridLayout.spec(i % 3, 1, 1f);
            params.rowSpec = GridLayout.spec(i / 3, 1, 1f);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            quickGrid.addView(slot, params);
        }
    }

    private void refreshVehicle() {
        if (vehicleRows == null) return;
        vehicleRows.removeAllViews();
        VehicleValue<?> speed = vehicleData.get(VehicleField.SPEED);
        if (speedGauge != null) {
            speedGauge.setSpeed(speed.isAvailable() && speed.value() instanceof Double ? (Double) speed.value() : null);
        }
        boolean autoHide = vehiclePreferences.getBoolean("auto_hide", false);
        int shown = 0;
        VehicleField[] dashboardFields = {VehicleField.SPEED, VehicleField.RANGE,
                VehicleField.CONSUMPTION, VehicleField.CLIMATE_TEMPERATURE};
        for (VehicleField field : dashboardFields) {
            String value = vehicleValue(field);
            if (autoHide && value == null) continue;
            if (shown > 0) vehicleRows.addView(horizontalDivider(), lp(-1, dp(1)));
            vehicleRows.addView(vehicleRow(field, value), lp(-1, 0, 1));
            if (++shown >= 5) break;
        }
        if (shown == 0) {
            TextView empty = txt("Sin datos visibles\nPulsa ⚙ para configurar", 12, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            vehicleRows.addView(empty, lp(-1, 0, 1));
        }
    }

    private void refreshStatus() {
        if (statusBar == null) return;
        statusBar.removeAllViews();

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(4), 0, dp(15), 0);
        heading.setBackground(statusHeadingBg());
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(statusBadgeBg());
        ImageView vehicleIcon = new ImageView(this);
        vehicleIcon.setImageResource(R.drawable.bmw_e87_front_status_v1);
        vehicleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vehicleIcon.setContentDescription("Estado del vehículo");
        FrameLayout.LayoutParams iconParams = frameLp(dp(46), dp(46), Gravity.CENTER);
        badge.addView(vehicleIcon, iconParams);
        heading.addView(badge, lp(dp(50), dp(50)));
        TextView headingText = txt("ESTADO DEL VEHÍCULO", 15, Color.rgb(124, 180, 239), false);
        headingText.setGravity(Gravity.CENTER_VERTICAL);
        headingText.setPadding(dp(10), 0, 0, 0);
        heading.addView(headingText, lp(0, -1, 1));
        statusBar.addView(heading, lp(dp(256), -1));
        statusBar.addView(statusDivider(), lp(dp(1), dp(34)));

        VehicleField[] fields = {VehicleField.LIGHTS, VehicleField.PARKING_BRAKE,
                VehicleField.SEATBELT, VehicleField.DOORS};
        for (VehicleField field : fields) {
            LinearLayout.LayoutParams params = lp(0, -1, 1);
            statusBar.addView(statusTile(field), params);
            statusBar.addView(statusDivider(), lp(dp(1), dp(34)));
        }
        LinearLayout.LayoutParams diagnosticParams = lp(0, -1, .95f);
        statusBar.addView(diagnosticStatusTile(), diagnosticParams);
    }

    private String vehicleValue(VehicleField field) {
        VehicleValue<?> value = vehicleData.get(field);
        if (!value.isAvailable() || value.value() == null) return null;
        if (value.value() instanceof Double) {
            double number = (Double) value.value();
            if (field == VehicleField.SPEED) return String.format(Locale.getDefault(), "%.0f km/h", number);
            if (field == VehicleField.RANGE) return String.format(Locale.getDefault(), "%.0f km", number);
            if (field == VehicleField.CONSUMPTION) return String.format(Locale.getDefault(), "%.1f l/100km", number);
            if (field == VehicleField.RPM) return String.format(Locale.getDefault(), "%.0f rpm", number);
            return String.format(Locale.getDefault(), "%.0f °C", number);
        }
        if (value.value() instanceof Boolean) return (Boolean) value.value() ? "Activo" : "Inactivo";
        if (value.value() instanceof CharSequence) return value.value().toString();
        // No arbitrary diagnostic extra is promoted to vehicle data.
        return null;
    }

    private View vehicleRow(VehicleField field, String value) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), 0, dp(5), 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(vehicleIcon(field));
        row.addView(icon, lp(dp(26), dp(26)));
        TextView label = txt(vehiclePanelLabel(field), 13, value == null ? MUTED : TEXT, false);
        label.setPadding(dp(8), 0, 0, 0);
        row.addView(label, lp(0, -1, 1));
        int readingColor = value == null ? MUTED : TEXT;
        if (field == VehicleField.SPEED && value != null) {
            VehicleValue<?> speed = vehicleData.get(VehicleField.SPEED);
            if (speed.isAvailable() && speed.value() instanceof Double) {
                readingColor = ((Double) speed.value()) > 120d
                        ? Color.rgb(246, 126, 13) : Color.rgb(72, 196, 118);
            }
        }
        TextView reading = txt(value == null ? "—" : value, 13, readingColor, false);
        reading.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(reading, lp(dp(92), -1));
        return row;
    }

    private int vehicleIcon(VehicleField field) {
        if (field == VehicleField.SPEED) return R.drawable.ic_vehicle_speed;
        if (field == VehicleField.RANGE) return R.drawable.ic_vehicle_range;
        if (field == VehicleField.CONSUMPTION) return R.drawable.ic_vehicle_consumption;
        return R.drawable.ic_vehicle_temperature;
    }

    private String vehiclePanelLabel(VehicleField field) {
        if (field == VehicleField.CONSUMPTION) return "Consumo";
        if (field == VehicleField.CLIMATE_TEMPERATURE) return "Temperatura";
        return field.label();
    }

    private View statusTile(VehicleField field) {
        String value = vehicleValue(field);
        int tone = statusTone(field, value);
        LinearLayout item = horizontal();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(8), dp(2), dp(5), dp(2));
        StatusIconView icon = new StatusIconView(this, statusIconKind(field), tone);
        icon.setContentDescription(statusLabel(field) + ": " + (value == null ? "sin fuente" : value));
        item.addView(icon, lp(dp(31), dp(31)));
        TextView state = txt(statusSentence(field, value), 10, value == null ? MUTED : TEXT, false);
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setMaxLines(1);
        state.setPadding(dp(7), 0, 0, 0);
        item.addView(state, lp(0, -1, 1));
        item.setOnClickListener(v -> diagnosticModal());
        return item;
    }

    private View diagnosticStatusTile() {
        LinearLayout item = horizontal();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(8), dp(2), dp(5), dp(2));
        StatusIconView icon = new StatusIconView(this, StatusIconKind.DIAGNOSTIC, BLUE);
        icon.setContentDescription("Abrir diagnóstico CANBUS");
        item.addView(icon, lp(dp(31), dp(31)));
        TextView copy = txt("Avisos  —", 10, MUTED, false);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(7), 0, 0, 0);
        item.addView(copy, lp(0, -1, 1));
        item.setOnClickListener(v -> diagnosticModal());
        return item;
    }

    private String statusSentence(VehicleField field, String value) {
        String label = statusLabel(field);
        if (value == null) return label + "  —";
        if (field == VehicleField.LIGHTS) return label + " " + value.toLowerCase(new Locale("es", "ES"));
        if (field == VehicleField.PARKING_BRAKE) return value.toLowerCase(Locale.ROOT).contains("activ")
                ? "Freno activado" : "Freno liberado";
        if (field == VehicleField.SEATBELT) return "Cinturón " + value.toLowerCase(new Locale("es", "ES"));
        if (field == VehicleField.DOORS) return "Puertas " + value.toLowerCase(new Locale("es", "ES"));
        return label + " " + value;
    }

    private View statusDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(40, 70, 98));
        return divider;
    }

    private StatusIconKind statusIconKind(VehicleField field) {
        if (field == VehicleField.LIGHTS) return StatusIconKind.LIGHTS;
        if (field == VehicleField.PARKING_BRAKE) return StatusIconKind.BRAKE;
        if (field == VehicleField.SEATBELT) return StatusIconKind.SEATBELT;
        return StatusIconKind.DOORS;
    }

    private int statusTone(VehicleField field, String value) {
        if (value == null) return Color.rgb(104, 128, 151);
        String normalized = value.toLowerCase(Locale.ROOT);
        if (field == VehicleField.LIGHTS) {
            if (normalized.contains("largas")) return Color.rgb(72, 150, 255);
            if (normalized.contains("antiniebla") || normalized.contains("emergencia")) return Color.rgb(245, 166, 35);
            if (normalized.contains("cruce") || normalized.contains("diurnas")) return Color.rgb(79, 205, 132);
            return Color.rgb(126, 151, 175);
        }
        if (field == VehicleField.PARKING_BRAKE) {
            return normalized.contains("activ") ? Color.rgb(238, 72, 66) : Color.rgb(79, 205, 132);
        }
        if (field == VehicleField.SEATBELT) {
            return normalized.contains("sin") ? Color.rgb(238, 72, 66) : Color.rgb(79, 205, 132);
        }
        if (field == VehicleField.DOORS) {
            return normalized.contains("cerrad") ? Color.rgb(79, 205, 132) : Color.rgb(245, 166, 35);
        }
        return BLUE;
    }

    private View horizontalDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(20, 45, 66));
        return divider;
    }

    private String statusLabel(VehicleField field) {
        if (field == VehicleField.PARKING_BRAKE) return "Freno de mano";
        if (field == VehicleField.SEATBELT) return "Cinturón";
        return field.label();
    }

    private boolean defaultVisible(VehicleField field) { return field.confirmedByUnit(); }

    private void vehicleModal() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = vertical();
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        scroll.addView(box);
        for (VehicleField field : VehicleField.values()) {
            Switch toggle = new Switch(this);
            toggle.setText(field.label() + (field.confirmedByUnit() ? "" : "  · pendiente de confirmar"));
            toggle.setTextColor(TEXT);
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
            toggle.setChecked(vehiclePreferences.getBoolean("show_" + field.key(), defaultVisible(field)));
            toggle.setOnCheckedChangeListener((button, checked) -> vehiclePreferences.edit()
                    .putBoolean("show_" + field.key(), checked).apply());
            box.addView(toggle);
        }
        CheckBox autoHide = new CheckBox(this);
        autoHide.setText("Ocultar automáticamente datos no disponibles");
        autoHide.setTextColor(TEXT);
        autoHide.setChecked(vehiclePreferences.getBoolean("auto_hide", false));
        autoHide.setOnCheckedChangeListener((button, checked) -> vehiclePreferences.edit().putBoolean("auto_hide", checked).apply());
        box.addView(autoHide);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Datos del vehículo")
                .setMessage("Solo se muestran valores reales. Si no existe una fuente confirmada se muestra —.")
                .setView(scroll).setPositiveButton("GUARDAR", (d, w) -> refreshAll())
                .setNeutralButton("DIAGNÓSTICO", (d, w) -> diagnosticModal())
                .setNegativeButton("CANCELAR", null).create();
        showSized(dialog, .78f, .84f);
    }

    private void diagnosticModal() {
        LinearLayout box = vertical();
        box.setBackgroundColor(PANEL);
        box.setPadding(dp(10), dp(4), dp(10), dp(4));
        TextView warning = txt("Modo pasivo. Prueba únicamente con el vehículo detenido y otra persona ayudando.\n\n"
                + "No transmite CAN, no escribe UART y no cambia ajustes OEM.", 12, ACCENT, false);
        warning.setPadding(dp(14), dp(8), dp(14), dp(8));
        box.addView(warning);
        TextView reportView = txt(buildDiagnosticReport(), 11, TEXT, false);
        reportView.setTextIsSelectable(true);
        ScrollView reportScroll = new ScrollView(this);
        reportScroll.addView(reportView);
        box.addView(reportScroll, lp(-1, 0, 1));
        LinearLayout actions = horizontal();
        Button start = dialogButton("INICIAR CORRELACIÓN");
        Button stop = dialogButton("DETENER");
        Button export = dialogButton("EXPORTAR");
        Button share = dialogButton("COMPARTIR");
        actions.addView(start, lp(0, dp(54), 1));
        actions.addView(stop, lp(0, dp(54), 1));
        actions.addView(export, lp(0, dp(54), 1));
        actions.addView(share, lp(0, dp(54), 1));
        box.addView(actions);
        LinearLayout usbRow = horizontal();
        TextView usbStatus = txt(usbDiagnostics.directorySummary(), 10, MUTED, false);
        usbStatus.setPadding(dp(12), 0, dp(8), 0);
        usbStatus.setGravity(Gravity.CENTER_VERTICAL);
        Button usb = dialogButton(usbCaptureRunning ? "USB DEBUG · ACTIVO" : "USB DEBUG");
        usbRow.addView(usbStatus, lp(0, dp(48), 1));
        usbRow.addView(usb, lp(dp(230), dp(48)));
        box.addView(usbRow);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Diagnóstico JCRK01 / CYA")
                .setView(box).setPositiveButton("CERRAR", null).create();
        start.setOnClickListener(v -> chooseCorrelation(dialog, reportView, start, stop));
        stop.setOnClickListener(v -> {
            if (!diagnostics.isCorrelationRunning()) { toast("No hay una sesión activa"); return; }
            if (usbCaptureRunning) {
                if (activeUsbWizard != null) activeUsbWizard.stopAndSave();
                return;
            }
            lastCorrelationReport = diagnostics.stopCorrelation();
            reportView.setText(lastCorrelationReport + "\n\n" + buildDiagnosticReport());
            start.setEnabled(true);
            stop.setEnabled(false);
        });
        export.setOnClickListener(v -> saveDiagnostic(true));
        share.setOnClickListener(v -> shareDiagnostic());
        usb.setOnClickListener(v -> usbDiagnosticMenu(reportView, start, stop, usb, usbStatus));
        dialog.setOnShowListener(v -> {
            stop.setEnabled(diagnostics.isCorrelationRunning());
            start.setEnabled(!diagnostics.isCorrelationRunning());
            resize(dialog, .86f, .88f);
        });
        dialog.show();
    }

    private void usbDiagnosticMenu(TextView reportView, Button start, Button stop, Button usb,
                                   TextView usbStatus) {
        if (!usbDiagnostics.hasDirectoryPermission()) {
            new AlertDialog.Builder(this).setTitle("Preparar memoria USB")
                    .setMessage(usbDiagnostics.removableVolumeDescription() + "\n\n"
                            + "Android abrirá su selector de carpetas. Elige una carpeta de la memoria USB y pulsa "
                            + "USAR ESTA CARPETA. La app no solicita acceso al resto del almacenamiento.")
                    .setPositiveButton("SELECCIONAR USB", (d, w) -> selectUsbDirectory(true))
                    .setNegativeButton("CANCELAR", null).show();
            return;
        }
        String[] options = usbCaptureRunning
                ? new String[]{"Detener y guardar captura", "Guardar snapshot ahora"}
                : new String[]{"Abrir asistente visual", "Guardar informe ahora", "Cambiar carpeta USB",
                "Olvidar autorización USB"};
        new AlertDialog.Builder(this).setTitle("USB DEBUG")
                .setItems(options, (d, which) -> {
                    if (usbCaptureRunning) {
                        if (which == 0) {
                            if (activeUsbWizard != null) activeUsbWizard.stopAndSave();
                        }
                        else if (which == 1) saveUsbSnapshot();
                    } else {
                        if (which == 0) showUsbDebugWizard(reportView, start, stop, usb, usbStatus);
                        else if (which == 1) saveUsbSnapshot();
                        else if (which == 2) selectUsbDirectory(false);
                        else {
                            usbDiagnostics.forgetDirectory();
                            usbStatus.setText(usbDiagnostics.directorySummary());
                            toast("Autorización USB eliminada");
                        }
                    }
                }).setNegativeButton("CANCELAR", null).show();
    }

    private void showUsbDebugWizard(TextView reportView, Button start, Button stop, Button usb,
                                    TextView usbStatus) {
        if (diagnostics.isCorrelationRunning()) {
            toast("Detén primero la sesión de correlación actual");
            return;
        }
        activeUsbWizard = new UsbDebugWizardDialog(this, diagnostics, usbDiagnostics,
                new UsbDebugWizardDialog.Host() {
                    @Override public String buildBaseReport() { return buildDiagnosticReport(); }

                    @Override public void ensureDiagnosticSourcesStarted() {
                        diagnostics.startPassiveProbe();
                        vehicleData.start();
                    }

                    @Override public void stopDiagnosticSourcesIfBackground() {
                        if (!foreground) {
                            diagnostics.stopPassiveProbe();
                            vehicleData.stop();
                        }
                    }

                    @Override public void onCaptureStateChanged(boolean running, String status) {
                        runOnUiThread(() -> {
                            usbCaptureRunning = running;
                            if (usb != null) usb.setText(running ? "USB DEBUG · ACTIVO" : "USB DEBUG");
                            if (usbStatus != null) usbStatus.setText(status);
                            if (start != null) start.setEnabled(!running);
                            if (stop != null) stop.setEnabled(running || diagnostics.isCorrelationRunning());
                        });
                    }

                    @Override public void onFinished(String finalCorrelationReport) {
                        runOnUiThread(() -> {
                            lastCorrelationReport = finalCorrelationReport;
                            if (reportView != null) reportView.setText(lastCorrelationReport + "\n\n"
                                    + buildDiagnosticReport());
                        });
                    }

                    @Override public void message(String value) { runOnUiThread(() -> toast(value)); }
                }, PANEL, PANEL2, TEXT, MUTED, LINE, BLUE, ACCENT);
        activeUsbWizard.showPlanPicker();
    }

    private void saveUsbSnapshot() {
        usbDiagnostics.saveReport("snapshot", buildUsbSnapshotReport(),
                this::usbCallback);
    }

    private void selectUsbDirectory(boolean beginCaptureAfterSelection) {
        startUsbCaptureAfterPicker = beginCaptureAfterSelection;
        try { startActivityForResult(usbDiagnostics.directoryPickerIntent(), REQUEST_USB_DIAGNOSTIC_DIRECTORY); }
        catch (Exception error) { toast("La radio no dispone de selector de almacenamiento: " + error.getMessage()); }
    }

    private String buildUsbSnapshotReport() {
        return "IDRIVE USB DEBUG · SNAPSHOT · JCRK01/CYA · SOLO LECTURA\n"
                + "No contiene tramas CAN/UART ni códigos propietarios confirmados.\n\n"
                + buildDiagnosticReport()
                + (lastCorrelationReport.isEmpty() ? "" : "\n\n" + lastCorrelationReport);
    }

    private void usbCallback(boolean success, String message) {
        runOnUiThread(() -> toast(message));
    }

    private void chooseCorrelation(AlertDialog dialog, TextView reportView, Button start, Button stop) {
        String[] options = {"Luces: apagadas / cruce / largas", "Freno de mano", "Puerta del conductor",
                "Cinturón del conductor", "Temperatura exterior", "Ventilador climatizador",
                "PDC / marcha atrás", "Otra señal"};
        new AlertDialog.Builder(this).setTitle("¿Qué vas a probar?").setItems(options, (d, which) -> {
            diagnostics.startCorrelation(options[which]);
            reportView.setText(diagnostics.correlationState() + "\n\n" + buildDiagnosticReport());
            start.setEnabled(false);
            stop.setEnabled(true);
            toast("Captura iniciada: " + options[which]);
        }).setNegativeButton("CANCELAR", null).show();
    }

    private void quickAppsModal() {
        GridLayout grid = quickGridForDialog();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Aplicaciones")
                .setMessage("Pulsa + para elegir una app instalada. Mantén pulsado para cambiar o eliminar.")
                .setView(grid).setPositiveButton("CERRAR", (d, w) -> refreshQuick()).create();
        showSized(dialog, .80f, .76f);
    }

    private GridLayout quickGridForDialog() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setRowCount(2);
        grid.setPadding(dp(12), dp(10), dp(12), dp(10));
        for (int i = 0; i < 6; i++) {
            String key = "quick_" + i;
            String pkg = apps.getPackage(key);
            TextView cell = txt(pkg == null ? "+\nAñadir app" : "▣\n" + apps.label(pkg), 14,
                    pkg == null ? ACCENT : TEXT, true);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(slotBg());
            cell.setOnClickListener(v -> {
                String selected = apps.getPackage(key);
                if (selected == null) appPicker(key, "Acceso rápido"); else launchKey(key);
            });
            cell.setOnLongClickListener(v -> { quickOptions(key, apps.getPackage(key)); return true; });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0; params.height = 0;
            params.columnSpec = GridLayout.spec(i % 3, 1, 1f);
            params.rowSpec = GridLayout.spec(i / 3, 1, 1f);
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(cell, params);
        }
        return grid;
    }

    private void settingsModal() {
        String[] options = {"Datos del vehículo", "Gasolineras: combustible y radio", "Accesos rápidos", "Cambiar Multimedia", "Cambiar Radio",
                "Cambiar Navegación", "Cambiar Android Auto", "Cambiar Teléfono / Bluetooth",
                "Acceso a contenido multimedia", "Diagnóstico JCRK01/CYA", "Modo día / noche", "Restaurar configuración"};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Ajustes iDrive")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: vehicleModal(); break;
                        case 1: fuelSettingsModal(); break;
                        case 2: quickAppsModal(); break;
                        case 3: appPicker("media", "Multimedia"); break;
                        case 4: appPicker("radio", "Radio"); break;
                        case 5: appPicker("nav", "Navegación"); break;
                        case 6: appPicker("auto", "Android Auto / S-Play"); break;
                        case 7: appPicker("phone", "Teléfono / Bluetooth"); break;
                        case 8: openMediaAccessSettings(); break;
                        case 9: diagnosticModal(); break;
                        case 10: themeModeModal(); break;
                        case 11:
                            apps.clearAll();
                            vehiclePreferences.edit().clear().apply();
                            uiPreferences.edit().clear().apply();
                            getSharedPreferences("fuel_widget", MODE_PRIVATE).edit().clear().apply();
                            fuelStations.configure(FuelStationProvider.DEFAULT_PRODUCT_ID,
                                    FuelStationProvider.DEFAULT_RADIUS_KM);
                            applyPalette();
                            buildUi();
                            autoDetectApps();
                            refreshAll();
                            break;
                    }
                }).setNegativeButton("CERRAR", null).create();
        showSized(dialog, .74f, .84f);
    }

    private void fuelSettingsModal() {
        String[] labels = new String[FuelStationProvider.FUELS.length];
        int current = 0;
        int productId = fuelStations.getProductId();
        for (int i = 0; i < FuelStationProvider.FUELS.length; i++) {
            labels[i] = FuelStationProvider.FUELS[i].label;
            if (FuelStationProvider.FUELS[i].productId == productId) current = i;
        }
        final int[] selected = {current};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Combustible · radio actual " + fuelStations.getRadiusKm() + " km")
                .setSingleChoiceItems(labels, current, (d, which) -> selected[0] = which)
                .setPositiveButton("ELEGIR RADIO", (d, w) -> fuelRadiusModal(
                        FuelStationProvider.FUELS[selected[0]].productId))
                .setNegativeButton("CANCELAR", null).create();
        showSized(dialog, .68f, .88f);
    }

    private void fuelRadiusModal(int productId) {
        int[] radii = {3, 5, 7, 10, 15, 20, 30, 50};
        String[] labels = new String[radii.length];
        int current = 2;
        for (int i = 0; i < radii.length; i++) {
            labels[i] = radii[i] + " km" + (radii[i] == FuelStationProvider.DEFAULT_RADIUS_KM
                    ? " · predeterminado" : "");
            if (radii[i] == fuelStations.getRadiusKm()) current = i;
        }
        final int[] selected = {current};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Radio desde la posición GPS")
                .setSingleChoiceItems(labels, current, (d, which) -> selected[0] = which)
                .setPositiveButton("APLICAR", (d, w) -> {
                    fuelStations.configure(productId, radii[selected[0]]);
                    toast("Gasolineras configuradas en " + radii[selected[0]] + " km");
                }).setNegativeButton("CANCELAR", null).create();
        showSized(dialog, .65f, .78f);
    }

    private void themeModeModal() {
        String[] modes = {"Automático (hora local)", "Día", "Noche"};
        String current = uiPreferences.getString("theme_mode", "auto");
        int checked = "day".equals(current) ? 1 : "night".equals(current) ? 2 : 0;
        final int[] selected = {checked};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Modo de interfaz")
                .setSingleChoiceItems(modes, checked, (d, which) -> selected[0] = which)
                .setPositiveButton("APLICAR", (d, w) -> {
                    uiPreferences.edit().putString("theme_mode", selected[0] == 1 ? "day" : selected[0] == 2 ? "night" : "auto").apply();
                    applyPalette();
                    buildUi();
                    refreshAll();
                }).setNegativeButton("CANCELAR", null).create();
        showSized(dialog, .70f, .55f);
    }

    private void openMediaAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            toast("Esta unidad no expone el ajuste de acceso multimedia");
        }
    }

    private void appPicker(String key, String title) {
        java.util.List<AppRepository.LaunchableApp> available = apps.launchableApps();
        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        scroll.addView(grid);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Elegir aplicación · " + title)
                .setMessage("La app se guarda por paquete y actividad. La elección manual prevalece sobre la detección automática.")
                .setView(scroll).setNegativeButton("QUITAR ASIGNACIÓN", (d, w) -> { apps.assign(key, null, true); refreshAll(); })
                .setNeutralButton("CANCELAR", null).create();
        for (AppRepository.LaunchableApp app : available) {
            LinearLayout cell = vertical();
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(5), dp(7), dp(5), dp(7));
            cell.setBackground(slotBg());
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            cell.addView(icon, lp(dp(46), dp(46)));
            TextView label = txt(app.label, 11, TEXT, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            cell.addView(label, lp(-1, dp(38)));
            cell.setOnClickListener(v -> { apps.assign(key, app, true); dialog.dismiss(); refreshAll(); });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(96);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(cell, params);
        }
        showSized(dialog, .82f, .84f);
    }

    private void quickOptions(String key, String pkg) {
        if (pkg == null) { appPicker(key, "Acceso rápido"); return; }
        new AlertDialog.Builder(this).setTitle(apps.label(pkg))
                .setItems(new String[]{"Abrir", "Cambiar aplicación", "Eliminar acceso"}, (d, which) -> {
                    if (which == 0) launchKey(key);
                    else if (which == 1) appPicker(key, "Acceso rápido");
                    else { apps.assign(key, null, true); refreshAll(); }
                }).show();
    }

    private void autoDetectApps() {
        boolean missing = false;
        for (String role : AppRepository.ROLES) {
            if (apps.getPackage(role) == null && !apps.isManual(role)) { missing = true; break; }
        }
        if (!missing) return;
        java.util.Map<String, AppRepository.LaunchableApp> detectedRoles = apps.detectRoles();
        for (String role : AppRepository.ROLES) {
            if (apps.getPackage(role) == null && !apps.isManual(role)) {
                AppRepository.LaunchableApp detected = detectedRoles.get(role);
                if (detected != null) apps.assign(role, detected, false);
            }
        }
    }

    private Bitmap decodeSampledResource(int resource, int requestedWidth, int requestedHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), resource, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= requestedWidth
                && bounds.outHeight / (sample * 2) >= requestedHeight) sample *= 2;
        BitmapFactory.Options decoded = new BitmapFactory.Options();
        decoded.inSampleSize = sample;
        decoded.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(getResources(), resource, decoded);
    }

    private void addMenu(LinearLayout side, String title, String role, int icon, boolean selected) {
        View item = menuItem(title, icon, selected);
        item.setOnClickListener(v -> launchRole(role));
        item.setOnLongClickListener(v -> { appPicker(role, title); return true; });
        side.addView(item, lp(-1, 0, 1));
    }

    private View menuItem(String label, int icon, boolean selected) {
        LinearLayout item = horizontal();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(18), 0, dp(8), 0);
        item.setBackground(selected ? selectedMenuBg() : menuBg());
        ImageView iconView = new ImageView(this);
        iconView.setImageResource(icon);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        item.addView(iconView, lp(dp(34), dp(34)));
        TextView labelView = txt(label, 16, TEXT, false);
        labelView.setPadding(dp(12), 0, 0, 0);
        labelView.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(labelView, lp(0, -1, 1));
        return item;
    }

    private void launchRole(String role) {
        if (apps.getPackage(role) == null) appPicker(role, role);
        else launchKey(role);
    }

    private void launchKey(String key) {
        Intent intent = apps.launchIntent(key);
        if (intent == null) {
            toast("La app asignada no está disponible");
            appPicker(key, "Cambiar aplicación");
            return;
        }
        try { startActivity(intent); }
        catch (Exception e) { toast("No se puede abrir la app asignada"); appPicker(key, "Cambiar aplicación"); }
    }

    private void requestLocationIfNeeded() {
        boolean locationMissing = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED;
        boolean bluetoothMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED;
        if (locationMissing || bluetoothMissing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.BLUETOOTH_CONNECT}, 100);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 100);
            }
        } else requestCarSpeedIfNeeded();
    }

    private void requestCarSpeedIfNeeded() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return;
        String permission = "android.car.permission.CAR_SPEED";
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return;
        if (uiPreferences.getBoolean("asked_car_speed", false)) return;
        uiPreferences.edit().putBoolean("asked_car_speed", true).apply();
        requestPermissions(new String[]{permission}, 101);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_USB_DIAGNOSTIC_DIRECTORY) return;
        boolean begin = startUsbCaptureAfterPicker;
        startUsbCaptureAfterPicker = false;
        if (resultCode != RESULT_OK || !usbDiagnostics.acceptDirectoryResult(data)) {
            toast("No se autorizó una carpeta escribible para USB DEBUG");
            return;
        }
        toast("Carpeta de diagnóstico autorizada");
        if (begin) showUsbDebugWizard(null, null, null, null, null);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            bluetoothState.stop();
            bluetoothState.start();
            vehicleData.stop();
            vehicleData.start();
            requestCarSpeedIfNeeded();
        } else if (requestCode == 101) {
            vehicleData.stop();
            vehicleData.start();
        }
    }

    private void startClock() {
        handler.post(new Runnable() {
            @Override public void run() {
                Date now = new Date();
                clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
                date.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("es", "ES"))
                        .format(now).toUpperCase(new Locale("es", "ES")));
                if (foreground && ++mediaRefreshTick % 2 == 0) {
                    refreshMedia();
                    refreshRadioWidget();
                }
                handler.postDelayed(this, 1000);
            }
        });
    }

    private void saveDiagnostic(boolean includeCorrelation) {
        try {
            File directory = getExternalFilesDir(null);
            if (directory == null) directory = getFilesDir();
            File output = new File(directory, "e87_diagnostic.txt");
            try (FileWriter writer = new FileWriter(output)) {
                writer.write(buildDiagnosticReport());
                if (includeCorrelation && !lastCorrelationReport.isEmpty()) writer.write("\n\n" + lastCorrelationReport);
                String recovery = usbDiagnostics.recoveryReport();
                if (!recovery.isEmpty()) writer.write("\n\nCOPIA DE RECUPERACIÓN USB DEBUG\n\n" + recovery);
            }
            toast("Diagnóstico guardado en " + output.getAbsolutePath());
        } catch (Exception e) { toast("No se pudo guardar: " + e.getMessage()); }
    }

    private void shareDiagnostic() {
        try {
            File directory = getExternalFilesDir(null);
            if (directory == null) directory = getFilesDir();
            File output = new File(directory, "e87_diagnostic.txt");
            try (FileWriter writer = new FileWriter(output)) {
                writer.write(buildDiagnosticReport());
                if (!lastCorrelationReport.isEmpty()) writer.write("\n\n" + lastCorrelationReport);
                String recovery = usbDiagnostics.recoveryReport();
                if (!recovery.isEmpty()) writer.write("\n\nCOPIA DE RECUPERACIÓN USB DEBUG\n\n" + recovery);
            }
            Uri uri = Uri.parse("content://" + getPackageName() + ".diagnostic/" + output.getName());
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico BMW E87 JCRK01/CYA")
                    .putExtra(Intent.EXTRA_TEXT, buildDiagnosticReport())
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir diagnóstico"));
        } catch (Exception e) { toast("No se pudo compartir: " + e.getMessage()); }
    }

    private String buildDiagnosticReport() {
        return diagnostics.buildReport() + "\n\n" + media.radioDiagnostic(apps.getPackage("radio"));
    }

    private void applyPalette() {
        boolean day = "day".equals(uiPreferences.getString("theme_mode", "auto"))
                || ("auto".equals(uiPreferences.getString("theme_mode", "auto")) && isDayByHour());
        if (day) {
            BG = Color.rgb(2, 8, 15); PANEL = Color.rgb(4, 14, 25); PANEL2 = Color.rgb(10, 27, 45);
            ACCENT = Color.rgb(246, 126, 13); BLUE = Color.rgb(111, 177, 244); TEXT = Color.rgb(242, 245, 248);
            MUTED = Color.rgb(157, 173, 190); LINE = Color.rgb(38, 72, 101);
        } else {
            BG = Color.rgb(1, 5, 10); PANEL = Color.rgb(3, 11, 20); PANEL2 = Color.rgb(7, 21, 36);
            ACCENT = Color.rgb(246, 126, 13); BLUE = Color.rgb(89, 157, 231); TEXT = Color.rgb(240, 243, 247);
            MUTED = Color.rgb(137, 156, 176); LINE = Color.rgb(29, 58, 84);
        }
    }

    private boolean isDayByHour() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 7 && hour < 19;
    }

    private void showSized(AlertDialog dialog, float width, float height) {
        dialog.setOnShowListener(v -> resize(dialog, width, height));
        dialog.show();
    }

    private void resize(AlertDialog dialog, float width, float height) {
        Window window = dialog.getWindow();
        if (window != null) window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * width),
                (int) (getResources().getDisplayMetrics().heightPixels * height));
    }

    private Button dialogButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setBackground(slotBg());
        return button;
    }

    private void hideUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private LinearLayout card() { LinearLayout view = vertical(); view.setPadding(dp(10), dp(8), dp(10), dp(8)); view.setBackground(cardBg()); return view; }
    private TextView txt(String value, float size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private GradientDrawable cardBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PANEL2, PANEL, Color.rgb(2, 11, 20)}); g.setCornerRadius(dp(11)); g.setStroke(dp(1), LINE); return g; }
    private GradientDrawable slotBg() { GradientDrawable g = new GradientDrawable(); g.setColor(Color.rgb(9, 22, 34)); g.setCornerRadius(dp(10)); g.setStroke(dp(1), LINE); return g; }
    private GradientDrawable menuBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.rgb(10, 23, 35), BG}); g.setCornerRadius(dp(8)); g.setStroke(dp(1), Color.rgb(29, 48, 64)); return g; }
    private GradientDrawable selectedMenuBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, Color.rgb(68, 45, 25), Color.rgb(11, 29, 46), BG, BG}); g.setCornerRadius(dp(8)); g.setStroke(dp(1), Color.rgb(36, 68, 96)); return g; }
    private GradientDrawable headerBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.rgb(8, 27, 49), Color.rgb(2, 9, 17), Color.rgb(4, 15, 28), Color.rgb(2, 8, 16)}); g.setStroke(dp(1), Color.rgb(29, 71, 113)); return g; }
    private GradientDrawable statusBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(7, 22, 38), Color.rgb(2, 10, 19)}); g.setCornerRadius(dp(11)); g.setStroke(dp(1), Color.rgb(29, 64, 95)); return g; }
    private GradientDrawable statusHeadingBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.rgb(10, 31, 54), Color.rgb(5, 18, 32), Color.TRANSPARENT}); g.setCornerRadius(dp(26)); return g; }
    private GradientDrawable statusBadgeBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.rgb(38, 84, 137), Color.rgb(6, 21, 40)}); g.setShape(GradientDrawable.OVAL); g.setStroke(dp(1), Color.rgb(72, 126, 181)); return g; }
    private GradientDrawable fuelRowBg(int tone) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.rgb(7, 21, 34), Color.rgb(4, 14, 24)}); g.setCornerRadius(dp(7)); g.setStroke(dp(1), Color.argb(115, Color.red(tone), Color.green(tone), Color.blue(tone))); return g; }
    private GradientDrawable sidePanelBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.rgb(9, 23, 38), Color.rgb(5, 15, 25)}); g.setCornerRadii(new float[]{dp(10), dp(10), dp(28), dp(28), dp(28), dp(28), dp(10), dp(10)}); g.setStroke(dp(1), Color.rgb(24, 50, 72)); return g; }
    private GradientDrawable carPanelBg() { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(7, 18, 29), BG}); g.setCornerRadius(dp(8)); return g; }
    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private LinearLayout.LayoutParams lp(int width, int height, float weight) { return new LinearLayout.LayoutParams(width, height, weight); }
    private FrameLayout.LayoutParams frameLp(int width, int height, int gravity) { FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height); params.gravity = gravity; return params; }
    /**
     * The target is a fixed 16:9 automotive canvas. Some head units expose 1280×720
     * at mdpi while others advertise phone-like densities; scaling by density would
     * make the same physical panel overflow. Keep this dashboard tied to pixels of
     * the available 1280×720 design canvas instead.
     */
    private float uiScale() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.min(metrics.widthPixels / 1280f, metrics.heightPixels / 720f);
    }
    private float px(float designPixels) { return designPixels * uiScale(); }
    private int dp(int value) { return (int) (px(value) + .5f); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    /** Decorative map surface only; it deliberately contains no simulated route or position. */
    private static final class NavigationPreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        NavigationPreviewView(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            canvas.drawColor(Color.rgb(5, 20, 32));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, h * .012f));
            paint.setColor(Color.rgb(18, 43, 57));
            float[] xs = {.12f, .31f, .55f, .76f, .91f};
            for (float x : xs) canvas.drawLine(w * x, 0, w * (x - .18f), h, paint);
            float[] ys = {.18f, .43f, .69f, .86f};
            for (float y : ys) canvas.drawLine(0, h * y, w, h * (y + .08f), paint);
            paint.setColor(Color.rgb(24, 64, 88));
            paint.setStrokeWidth(Math.max(2f, h * .02f));
            canvas.drawLine(w * .72f, 0, w * .55f, h, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(234, 240, 246));
            arrow.reset();
            arrow.moveTo(w * .50f, h * .22f);
            arrow.lineTo(w * .38f, h * .78f);
            arrow.lineTo(w * .51f, h * .68f);
            arrow.lineTo(w * .62f, h * .79f);
            arrow.close();
            canvas.drawPath(arrow, paint);
        }
    }

    /** Displays the BMW iDrive header artwork supplied by the user, cropped to its banner area. */
    private static final class HeaderBrandView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Bitmap bitmap;
        private final Rect source = new Rect(16, 23, 590, 114);
        private final Rect destination = new Rect();

        HeaderBrandView(Context context) {
            super(context);
            bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.bmw_idrive_header_reference);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            destination.set(0, 0, getWidth(), getHeight());
            if (bitmap != null) canvas.drawBitmap(bitmap, source, destination, paint);
        }
    }

    private enum StatusIconKind { VEHICLE, LIGHTS, BRAKE, SEATBELT, DOORS, DIAGNOSTIC }

    /** Dashboard-style vehicle pictograms drawn locally, with no dependence on vendor assets. */
    private static final class StatusIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final StatusIconKind kind;
        private final int color;

        StatusIconView(Context context, StatusIconKind kind, int color) {
            super(context);
            this.kind = kind;
            this.color = color;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            float cx = w / 2f, cy = h / 2f;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.7f, s * .055f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (kind == StatusIconKind.LIGHTS) drawLights(canvas, w, h);
            else if (kind == StatusIconKind.BRAKE) drawBrake(canvas, cx, cy, s);
            else if (kind == StatusIconKind.SEATBELT) drawSeatbelt(canvas, w, h);
            else if (kind == StatusIconKind.DOORS) drawDoors(canvas, w, h);
            else if (kind == StatusIconKind.DIAGNOSTIC) drawDiagnostic(canvas, cx, cy, s);
            else drawVehicle(canvas, w, h);
        }

        private void drawLights(Canvas canvas, float w, float h) {
            RectF lamp = new RectF(w * .15f, h * .22f, w * .50f, h * .78f);
            canvas.drawArc(lamp, -90, 180, false, paint);
            canvas.drawLine(w * .33f, h * .22f, w * .33f, h * .78f, paint);
            for (int i = 0; i < 3; i++) {
                float y = h * (.31f + i * .19f);
                canvas.drawLine(w * .57f, y, w * .87f, y - h * .07f, paint);
            }
        }

        private void drawBrake(Canvas canvas, float cx, float cy, float s) {
            canvas.drawCircle(cx, cy, s * .29f, paint);
            canvas.drawArc(new RectF(cx - s * .46f, cy - s * .35f, cx - s * .08f, cy + s * .35f), 105, 150, false, paint);
            canvas.drawArc(new RectF(cx + s * .08f, cy - s * .35f, cx + s * .46f, cy + s * .35f), -75, 150, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(s * .40f);
            canvas.drawText("P", cx, cy + s * .145f, paint);
        }

        private void drawSeatbelt(Canvas canvas, float w, float h) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(w * .39f, h * .22f, Math.min(w, h) * .09f, paint);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(w * .37f, h * .34f, w * .31f, h * .72f, paint);
            canvas.drawLine(w * .31f, h * .72f, w * .58f, h * .77f, paint);
            canvas.drawLine(w * .43f, h * .37f, w * .68f, h * .73f, paint);
            canvas.drawLine(w * .27f, h * .33f, w * .66f, h * .81f, paint);
        }

        private void drawDoors(Canvas canvas, float w, float h) {
            RectF body = new RectF(w * .34f, h * .14f, w * .66f, h * .86f);
            canvas.drawRoundRect(body, w * .10f, w * .10f, paint);
            canvas.drawLine(w * .37f, h * .40f, w * .18f, h * .28f, paint);
            canvas.drawLine(w * .37f, h * .59f, w * .18f, h * .72f, paint);
            canvas.drawLine(w * .63f, h * .40f, w * .82f, h * .28f, paint);
            canvas.drawLine(w * .63f, h * .59f, w * .82f, h * .72f, paint);
        }

        private void drawDiagnostic(Canvas canvas, float cx, float cy, float s) {
            canvas.drawCircle(cx, cy, s * .34f, paint);
            path.reset();
            path.moveTo(cx - s * .17f, cy);
            path.lineTo(cx - s * .04f, cy + s * .13f);
            path.lineTo(cx + s * .20f, cy - s * .15f);
            canvas.drawPath(path, paint);
        }

        private void drawVehicle(Canvas canvas, float w, float h) {
            float s = Math.min(w, h);
            paint.setStrokeWidth(Math.max(1.6f, s * .055f));
            path.reset();
            path.moveTo(w * .24f, h * .48f);
            path.lineTo(w * .34f, h * .25f);
            path.quadTo(w * .50f, h * .17f, w * .66f, h * .25f);
            path.lineTo(w * .76f, h * .48f);
            canvas.drawPath(path, paint);
            canvas.drawRoundRect(new RectF(w * .18f, h * .43f, w * .82f, h * .76f),
                    s * .08f, s * .08f, paint);
            canvas.drawLine(w * .34f, h * .29f, w * .66f, h * .29f, paint);
            canvas.drawLine(w * .29f, h * .47f, w * .71f, h * .47f, paint);
            canvas.drawCircle(w * .31f, h * .57f, s * .045f, paint);
            canvas.drawCircle(w * .69f, h * .57f, s * .045f, paint);
            canvas.drawLine(w * .42f, h * .58f, w * .58f, h * .58f, paint);
            canvas.drawLine(w * .30f, h * .76f, w * .30f, h * .84f, paint);
            canvas.drawLine(w * .70f, h * .76f, w * .70f, h * .84f, paint);
        }
    }

    /** Gauge draws only actual GPS speed when it is available; otherwise the value stays unavailable. */
    private static final class SpeedGaugeView extends View {
        private static final double GREEN_LIMIT_KMH = 120d;
        private static final double GAUGE_MAX_KMH = 260d;
        private static final int SPEED_GREEN = Color.rgb(72, 196, 118);
        private static final int SPEED_ORANGE = Color.rgb(246, 126, 13);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private Double speed;

        SpeedGaugeView(Context context) { super(context); }

        void setSpeed(Double speed) { this.speed = speed; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float size = Math.min(width, height) * .91f;
            float left = (width - size) / 2f;
            float top = (height - size) / 2f;
            float centerX = width / 2f;
            float centerY = top + size / 2f;
            arc.set(left, top, left + size, top + size);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * .012f));
            paint.setColor(Color.rgb(26, 68, 112));
            canvas.drawArc(arc, 135, 270, false, paint);
            arc.inset(size * .035f, size * .035f);
            paint.setStrokeWidth(Math.max(1f, size * .006f));
            paint.setColor(Color.rgb(126, 157, 187));
            canvas.drawArc(arc, 205, 130, false, paint);
            arc.inset(-size * .035f, -size * .035f);
            Double displaySpeed = speed != null && Double.isFinite(speed) ? Math.max(0d, speed) : null;
            if (displaySpeed != null && displaySpeed > 0d) {
                double clamped = Math.min(displaySpeed, GAUGE_MAX_KMH);
                float greenSweep = (float) (270d * Math.min(clamped, GREEN_LIMIT_KMH) / GAUGE_MAX_KMH);
                paint.setStrokeWidth(Math.max(3f, size * .020f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(SPEED_GREEN);
                canvas.drawArc(arc, 135, greenSweep, false, paint);
                if (clamped > GREEN_LIMIT_KMH) {
                    float orangeSweep = (float) (270d * (clamped - GREEN_LIMIT_KMH) / GAUGE_MAX_KMH);
                    paint.setColor(SPEED_ORANGE);
                    canvas.drawArc(arc, 135 + greenSweep, orangeSweep, false, paint);
                }
                paint.setStrokeCap(Paint.Cap.BUTT);
            }
            for (int i = 0; i <= 30; i++) {
                double radians = Math.toRadians(135 + i * (270d / 30d));
                float outer = size * .49f;
                float inner = outer - (i % 5 == 0 ? size * .07f : size * .035f);
                float x1 = centerX + (float) Math.cos(radians) * outer;
                float y1 = centerY + (float) Math.sin(radians) * outer;
                float x2 = centerX + (float) Math.cos(radians) * inner;
                float y2 = centerY + (float) Math.sin(radians) * inner;
                paint.setStrokeWidth(i % 5 == 0 ? Math.max(2f, size * .012f) : Math.max(1f, size * .006f));
                paint.setColor(i > 26 ? Color.rgb(231, 74, 34) : Color.rgb(73, 159, 244));
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
            String value = displaySpeed == null ? "—" : String.format(Locale.getDefault(), "%.0f", displaySpeed);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            paint.setTextSize(size * .29f);
            paint.setColor(displaySpeed == null ? Color.rgb(243, 246, 249)
                    : displaySpeed > GREEN_LIMIT_KMH ? SPEED_ORANGE : SPEED_GREEN);
            canvas.drawText(value, centerX, centerY + size * .08f, paint);
            paint.setTextSize(size * .105f);
            paint.setColor(Color.rgb(154, 170, 186));
            canvas.drawText("km/h", centerX, centerY + size * .23f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(size * .09f);
            canvas.drawText("0", left + size * .13f, top + size * .86f, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("260", left + size * .87f, top + size * .86f, paint);
        }
    }

}
