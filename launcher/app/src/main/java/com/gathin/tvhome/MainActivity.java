package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Number of visible app slots and pinned favourites (res/values/config.xml, columns). */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private static final long HOME_INTERNET_WALLPAPER_INTERVAL_MS = 3L * 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    /** The focus: one dark shape gliding between tiles and pills (see Glider). */
    private Glider glider;
    /** Whether the glider is on the top menu, so a move down travels dark. */
    private boolean gliderOnPill;
    private final int[] pillAt = new int[2], rootAt = new int[2];
    /** The one-line place name may use up to this share of the screen. */
    private static final float HERO_NAME_WIDTH_SHARE = 0.60f;
    private static final float HERO_TITLE_MAX_SP = 40f;
    /** The breathing room between the shelf's last line and the top of the app container. */
    private static final float HERO_GAP_DP = 67f;
    private static final float HERO_TITLE_MIN_SP = 26f;
    /** The shelf's width in pixels, worked out once from the screen. */
    private int heroNameMaxWidth;
    private View heroShelf;
    private TextView heroName;
    private TextView heroWhere;
    /** The place on screen and its location; null for the user's own wallpapers. */
    private String placeName;
    private int ambientColor = -1;
    private boolean heroShown;
    /** The glow behind the tiles. It follows every app, while the shelf only names dock apps. */
    private boolean ambientShown;

    /** The colour the glass is blending from, how far along it is, and the blend itself. */
    private int glowFrom;
    private float glowMix = 1f;
    private android.animation.ValueAnimator glowBlend;
    private Bitmap ambientShape;
    /** Where the shelf's bottom edge belongs; its top follows once the lines have been measured. */
    private float heroBottom;
    private View btnInputs;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;
    /** Today's Indonesian prayer times; hidden until Android provides a location and city. */
    private TextView prayerRow;
    private boolean prayerRefreshRunning;
    private boolean prayerCityResolveRunning;
    private long lastPrayerCityAttempt;
    private Location lastPrayerCityLocation;
    private TextView weatherLine;
    private boolean showWeather, autoLocation, downloadMasjid, downloadAzan;
    private boolean weatherRefreshRunning, locationUpdatesStarted, azanAudioDownloadRunning;
    private long lastWeatherRefresh;
    private Location latestLocation;
    private LocationManager locationManager;
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location location) { acceptLocation(location); }
        @Override public void onProviderEnabled(@NonNull String provider) { readLastKnownLocation(); }
        @Override public void onProviderDisabled(@NonNull String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };
    /** The azan over Home at prayer times; null when the theme turns it off. */
    private Azan azan;
    private boolean azanReducedMotion;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private boolean tilesAnimatedOnce;
    /** The longest Home holds its first frame while it puts itself together (see holdFirstFrame). */
    private static final long FIRST_FRAME_WAIT_MS = 1200;
    private static final long RETURN_SETTLE_MS = 6000;
    /** True while Home rebuilds on return; focus passes through the pills then and must not light them. */
    private boolean resuming;
    /** True while Home's first frame is held back (see holdFirstFrame). */
    private boolean firstFrameHeld;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Commons wallpaper URLs stay in memory; only the currently displayed bitmap is retained. */
    private List<HomeWallpapers.Photo> internetWallpapers = Collections.emptyList();
    /** Optional theme wallpapers used when masjid_home is disabled. */
    private List<File> localWallpapers = Collections.emptyList();
    private volatile boolean homeWallpaperRefreshRunning;
    /** Decodes the next picture off the main thread, so a switch never stutters the D-pad animations. */
    private final java.util.concurrent.ExecutorService wallDecoder = java.util.concurrent.Executors.newSingleThreadExecutor();
    private long wallShownAt;
    private android.widget.ImageView wallView;
    private android.graphics.Bitmap wallBitmap;
    /** Settings kept for the places refresh, which runs after onCreate. */
    private Config config;
    private SoundPool focusSoundPool;
    private int focusSoundId;
    private boolean focusSoundLoaded;
    private int lastSoundFocus = -1;
    /** Whether the currently shown wallpaper has place-specific dimming and header text. */
    private boolean placesMode;
    /** Kept for legacy place metadata; the screensaver's Masjid photo source remains separate. */
    private boolean masjidMode;
    private org.json.JSONObject masjidCaptions;
    private volatile boolean masjidRefreshRunning;
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(() -> {
                updateWifiLabel();
                syncHomeWallpaperSource();
            });
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                handler.post(MainActivity.this::syncHomeWallpaperSource);
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(() -> {
                updateWifiLabel();
                if (!hasValidatedInternet()) {
                    handler.removeCallbacks(wallTick);
                }
            });
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateHeaderDate();
            updateShamsiDate();
            updatePrayerTimes();
            updateWeather();
            checkAzan();
            updateWifiLabel();
            syncHomeWallpaperSource();
            refreshMasjidPhotos();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prepareFocusSound();
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        COLUMNS = cfg.columns;
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        // The tiles' entrance plays once per TV boot. Android reclaims the launcher during long stays
        // in big apps; starting again then is a return to Home, not a first launch.
        long bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        android.content.SharedPreferences launch = getSharedPreferences("launch", MODE_PRIVATE);
        if (Math.abs(launch.getLong("boot_at", 0) - bootAt) < 60_000) tilesAnimatedOnce = true;
        else launch.edit().putLong("boot_at", bootAt).apply();
        root.setBackgroundColor(cfg.backgroundColor);
        config = cfg;
        HomeWallpapers.clearLegacyCache(this);
        placesMode = false;
        masjidMode = false;
        if (!cfg.masjidHome) {
            localWallpapers = !cfg.wallpapers.isEmpty() ? cfg.wallpapers
                    : cfg.wallpaper != null ? Collections.singletonList(cfg.wallpaper) : Collections.emptyList();
            if (!localWallpapers.isEmpty()) showNextLocalWallpaper(false);
        }
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        Backdrop.setWorker(wallDecoder, handler);
        heroShelf = findViewById(R.id.heroShelf);
        heroName = findViewById(R.id.heroName);
        heroWhere = findViewById(R.id.heroWhere);
        // The name may use more of the screen than the short location line.
        int usable = getResources().getDisplayMetrics().widthPixels - (int) ((55 + 56) * density);
        heroNameMaxWidth = Math.round(usable * HERO_NAME_WIDTH_SHARE);
        heroName.setMaxWidth(heroNameMaxWidth);
        // The shelf grows and shrinks with the name and location, so it hangs from its
        // bottom edge: every time it is measured, its top is worked out again. Positioning it by
        // the height it happened to have beforehand is what let a tall block run into the dock.
        heroShelf.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (heroBottom > 0) v.setY(heroBottom - v.getHeight());
        });
        btnWifi = findViewById(R.id.btnWifi);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        View btnHome = findViewById(R.id.btnHome);
        if (!cfg.showHomeSettingsShortcut) btnHome.setVisibility(View.GONE);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        prayerRow = findViewById(R.id.prayerRow);
        weatherLine = findViewById(R.id.weatherLine);
        showWeather = cfg.weather;
        autoLocation = cfg.autoLocation;
        downloadMasjid = cfg.downloadMasjid;
        downloadAzan = cfg.downloadAzan;
        if (!showWeather) weatherLine.setVisibility(View.GONE);
        updateHeaderDate();
        if (cfg.azan) {
            azan = new Azan(root, () -> {
                View f = getCurrentFocus();
                if (f != null) f.requestFocus();
            });
            azanReducedMotion = cfg.reducedMotion;
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false) {
            @Override
            public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
                // Place the single horizontal app row before laying it out, so it stays at the
                // bottom of the screen after Home is rebuilt.
                parkDock();
                super.onLayoutChildren(recycler, state);
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        DockDecoration tray = new DockDecoration(Integer.MAX_VALUE, density, cfg.showDockTray);
        grid.addItemDecoration(tray);
        // Glass shows the blurred wallpaper behind it: the dock tray (drawn by the grid) and the
        // top-bar pills redraw when a new blurred copy is ready.
        Backdrop.watch(grid);
        setUpGlider();
        holdFirstFrame();
        for (int i = 0; i < grid.getItemDecorationCount(); i++) {
            if (grid.getItemDecorationAt(i) instanceof DockDecoration) {
                ((DockDecoration) grid.getItemDecorationAt(i)).setGlider(glider);
            }
        }
        // The grid only shows the shape on the tray.
        glider.addCompanion(grid, tray.area);
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnHome, R.id.btnSettings}) {
            View pill = findViewById(id);
            pill.setBackground(new GlassDrawable(pill, 21 * density, density, glider));
            glider.addCompanion(pill);
            Backdrop.watch(pill);
            pill.setOnFocusChangeListener((v, has) -> {
                if (has) moveTopHighlight(v);
            });
        }
        // The apps rise into place on the first launch after the TV starts, and only then. Setting
        // the animation arms it for the grid's first draw, so on any later fresh start (Android
        // closing Home while an app was open) it is not set at all: armed, it drew the dock's tray
        // empty while the apps faded in.
        if (!tilesAnimatedOnce) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        grid.setAdapter(adapter);

        // Keep the horizontal app row near the bottom of the screen.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            parkDock();
            updateTopFade();
        });

        // Rows feather out under the top bar instead of being cut off. The fade needs an offscreen
        // layer and draws the grid twice, on every frame of every animation, so it is on only while a
        // row is actually in the band: it switches on in the same frame a row scrolls into it.
        gridWrap = findViewById(R.id.gridWrap);

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateTopFade();
                if (!glassShown) return;
                glassTargetX -= dx;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glider.scrolled(dx, dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                // Critically damped: glides onto the tile and stops there, with no bounce back.
                s.setSpring(new SpringForce().setStiffness(900f).setDampingRatio(1.0f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnHome.setOnClickListener(v -> open(new Intent(this, HomeSettingsActivity.class)));
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showLauncherOptions(v);
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(Math.max(0, lastFocused));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                int id = v.getId();
                if (id == R.id.btnInputs) return true;
                if (id == R.id.btnWifi) return btnInputs.getVisibility() != View.VISIBLE;
                if (id == R.id.btnHome) {
                    if (btnWifi.getVisibility() == View.VISIBLE) btnWifi.requestFocus();
                    else if (btnInputs.getVisibility() == View.VISIBLE) btnInputs.requestFocus();
                    return true;
                }
                if (id == R.id.btnSettings) {
                    if (btnHome.getVisibility() == View.VISIBLE) btnHome.requestFocus();
                    else if (btnWifi.getVisibility() == View.VISIBLE) btnWifi.requestFocus();
                    else if (btnInputs.getVisibility() == View.VISIBLE) btnInputs.requestFocus();
                    return true;
                }
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnHome.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
            if (!onTile && (newF == null || !(isTopPill(newF) || inMenu(newF)))) glider.hide();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private android.graphics.Bitmap decodeWallpaper(byte[] bytes) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
            return null;
        }
    }

    /** Decodes a theme wallpaper to screen size without loading its full-resolution source. */
    private android.graphics.Bitmap decodeWallpaper(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels
                    && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w("TvHome.ThemeWallpaper", "could not decode " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Puts a picture behind everything, cross-fading from the previous one when asked. */
    private void setWallpaper(android.graphics.Bitmap bmp, boolean fade) {
        setWallpaper(bmp, fade, null);
    }

    /** With {@code blur} made in advance, the glass changes in step with the picture. */
    private void setWallpaper(android.graphics.Bitmap bmp, boolean fade, android.graphics.Bitmap blur) {
        if (bmp == null) return;
        if (wallView == null) {
            wallView = new android.widget.ImageView(this);
            wallView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            // Photos are bright: dim them so the clock, names and tiles stay readable.
            if (placesMode) wallView.setColorFilter(0xFF7A7A7A, android.graphics.PorterDuff.Mode.MULTIPLY);
            root.addView(wallView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        android.graphics.Bitmap old = wallBitmap;
        wallBitmap = bmp;
        if (fade && old != null && !reducedMotion) {
            android.graphics.drawable.TransitionDrawable t = new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.BitmapDrawable(getResources(), old),
                    new android.graphics.drawable.BitmapDrawable(getResources(), bmp)});
            t.setCrossFadeEnabled(true);
            wallView.setImageDrawable(t);
            t.startTransition(800);
            // The glass fades to the new picture over the same 800 ms as the wallpaper itself.
            if (blur != null) Backdrop.present(blur, 800);
            else blurBackdrop(bmp);
            // Drop the old picture once the fade is done, so only one 1080p bitmap stays in memory.
            handler.postDelayed(() -> {
                if (wallBitmap == bmp) wallView.setImageBitmap(bmp);
            }, 900);
        } else {
            wallView.setImageBitmap(bmp);
            if (blur != null) Backdrop.present(blur, 0);
            else blurBackdrop(bmp);
        }
        // An opaque picture covers the whole screen, so the black window and the plain background
        // under it are never seen. Painting them anyway cost two full-screen passes on every frame,
        // and this TV's graphics chip, not the app, is what holds each frame back.
        if (!bmp.hasAlpha()) {
            root.setBackground(null);
            getWindow().setBackgroundDrawable(null);
        }
    }

    /** Hands the picture now on screen to the glass, which blurs it once, off the main thread. */
    private void blurBackdrop(android.graphics.Bitmap bmp) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Backdrop.update(bmp, dm.widthPixels, dm.heightPixels, placesMode ? 0xFF7A7A7A : 0, wallDecoder, handler);
    }

    /** Downloads and shows the next Commons photo; image bytes are never written to storage. */
    private void showNextWallpaper(boolean fade) {
        if (internetWallpapers.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("home_wallpaper", MODE_PRIVATE);
        int next = (prefs.getInt("index", -1) + 1) % internetWallpapers.size();
        prefs.edit().putInt("index", next).apply();
        wallShownAt = android.os.SystemClock.elapsedRealtime();
        final HomeWallpapers.Photo photo = internetWallpapers.get(next);
        final boolean firstPicture = wallBitmap == null;
        wallDecoder.execute(() -> {
            android.graphics.Bitmap bmp = null;
            try {
                byte[] bytes = HomeWallpapers.download(photo);
                bmp = decodeWallpaper(bytes);
            } catch (Exception | OutOfMemoryError e) {
                android.util.Log.w("TvHome.HomeWallpaper", "could not load Commons photo: " + e.getMessage());
            }
            if (bmp == null) return;
            // After the first picture, the blur is made here, before the picture is shown, so the glass
            // changes with the wallpaper rather than after it. The first picture of a fresh start does
            // not wait for its blur: Home is held for it, and the glass catches up a moment later.
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            final android.graphics.Bitmap decoded = bmp;
            android.graphics.Bitmap blur = firstPicture ? null
                    : Backdrop.blur(decoded, dm.widthPixels, dm.heightPixels, placesMode ? 0xFF7A7A7A : 0);
            final android.graphics.Bitmap blurred = blur;
            handler.post(() -> {
                if (isDestroyed() || !hasValidatedInternet()) {
                    if (!decoded.isRecycled()) decoded.recycle();
                    if (blurred != null && !blurred.isRecycled()) blurred.recycle();
                    return;
                }
                wallShownAt = android.os.SystemClock.elapsedRealtime();
                setWallpaper(decoded, fade, blurred);
                setPlace(photo);
            });
        });
    }

    /** Switches to the next Internet photo every three minutes while Home is in front. */
    private final Runnable wallTick = new Runnable() {
        @Override
        public void run() {
            if (config != null && !config.masjidHome) {
                if (localWallpapers.size() >= 2) showNextLocalWallpaper(true);
                if (config.wallpaperIntervalMs > 0 && localWallpapers.size() >= 2)
                    handler.postDelayed(this, config.wallpaperIntervalMs);
                return;
            }
            if (!hasValidatedInternet()) {
                return;
            }
            if (internetWallpapers.size() >= 2) showNextWallpaper(true);
            handler.postDelayed(this, HOME_INTERNET_WALLPAPER_INTERVAL_MS);
        }
    };

    /** Shows a theme wallpaper, advancing its local index without keeping full-size files in memory. */
    private void showNextLocalWallpaper(boolean fade) {
        if (localWallpapers.isEmpty()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("theme_wallpaper", MODE_PRIVATE);
        int next = (prefs.getInt("index", -1) + 1) % localWallpapers.size();
        prefs.edit().putInt("index", next).apply();
        File file = localWallpapers.get(next);
        wallShownAt = android.os.SystemClock.elapsedRealtime();
        wallDecoder.execute(() -> {
            android.graphics.Bitmap bmp = decodeWallpaper(file);
            if (bmp == null) return;
            handler.post(() -> {
                if (isDestroyed() || (config != null && config.masjidHome)) {
                    if (!bmp.isRecycled()) bmp.recycle();
                    return;
                }
                setWallpaper(bmp, fade);
                setPlace((HomeWallpapers.Photo) null);
            });
        });
    }

    /** Fetches only Commons photo metadata; each selected photo is downloaded into memory when displayed. */
    private void syncHomeWallpaperSource() {
        if (config == null || !config.masjidHome) return;
        if (!hasValidatedInternet()) {
            handler.removeCallbacks(wallTick);
            return;
        }
        if (homeWallpaperRefreshRunning || !HomeWallpapers.due(this, internetWallpapers.size())) return;
        homeWallpaperRefreshRunning = true;
        new Thread(() -> {
            List<HomeWallpapers.Photo> photos = Collections.emptyList();
            try {
                photos = HomeWallpapers.refresh(getApplicationContext());
            } finally {
                final List<HomeWallpapers.Photo> result = photos;
                handler.post(() -> {
                    homeWallpaperRefreshRunning = false;
                    if (isDestroyed()) return;
                    if (result.isEmpty()) return;
                    internetWallpapers = result;
                    placesMode = false;
                    masjidMode = false;
                    masjidCaptions = null;
                    if (hasWindowFocus()) {
                        showNextWallpaper(wallBitmap != null);
                        startWallpaperRotation();
                    }
                });
            }
        }, "home-wallpapers").start();
    }

    private boolean hasValidatedInternet() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** Keeps the original local mosque photo download for the screensaver; Home only fetches manifest URLs. */
    private void refreshMasjidPhotos() {
        if (!downloadMasjid || masjidRefreshRunning || !Masjid.due(this)) return;
        masjidRefreshRunning = true;
        new Thread(() -> {
            Masjid.refresh(getApplicationContext());
            handler.post(() -> {
                masjidRefreshRunning = false;
            });
        }, "mosque-photos").start();
    }

    /** Downloads missing default azan MP3s in the background to the app's local files. */
    private void ensureAzanAudio() {
        if (azanAudioDownloadRunning) return;
        azanAudioDownloadRunning = true;
        new Thread(() -> {
            try {
                AzanAudio.ensureDownloaded(getApplicationContext());
            } finally {
                handler.post(() -> azanAudioDownloadRunning = false);
            }
        }, "azan-audio-download").start();
    }

    /** Clears the TV's app caches when due, once Home has sat idle on screen for a while. */
    private final Runnable cacheClean = () -> {
        if (hasWindowFocus() && config != null) CacheCleaner.maybeRun(this, config.cacheCleanDays);
    };

    /** Whenever Home comes back: resumes the three-minute rotation where the interval left it. */
    private void startWallpaperRotation() {
        refreshMasjidPhotos();
        syncHomeWallpaperSource();
        handler.removeCallbacks(wallTick);
        if (config != null && !config.masjidHome) {
            if (localWallpapers.size() < 2 || config.wallpaperIntervalMs <= 0) return;
            long waited = android.os.SystemClock.elapsedRealtime() - wallShownAt;
            handler.postDelayed(wallTick, Math.max(RETURN_SETTLE_MS, config.wallpaperIntervalMs - waited));
            return;
        }
        if (!hasValidatedInternet()) return;
        if (internetWallpapers.size() < 2) return;
        long waited = android.os.SystemClock.elapsedRealtime() - wallShownAt;
        // A picture that fell due while you were in an app waits a few seconds: decoding, blurring
        // and fading it the moment you come back competed with Home for the TV's slow CPU.
        handler.postDelayed(wallTick, Math.max(RETURN_SETTLE_MS, HOME_INTERNET_WALLPAPER_INTERVAL_MS - waited));
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        // Home pressed on Home goes back to the first app. Home pressed in another app comes back to
        // the app you left from: resetting then made the selection run from it to the first app.
        if (hiddenSinceResume) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
    }

    /**
     * Home went behind another app since it was last resumed. A Home press that brings it back
     * arrives before onResume, so this tells it apart from a Home press on Home itself.
     */
    private boolean hiddenSinceResume;

    @Override
    protected void onStop() {
        hiddenSinceResume = true;
        handler.removeCallbacks(cacheClean);
        stopLocationUpdates();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startAutoLocation();
        if (downloadMasjid) refreshMasjidPhotos();
        if (downloadAzan && config != null && config.azan) ensureAzanAudio();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        tilesReady = false;
        promptsPaused = false;
        resuming = true;
        reloadApps(true);
        updateShamsiDate();
        updateHeaderDate();
        updatePrayerTimes();
        Azan.homeResumed = true;
        checkAzan();
        AzanAlarm.scheduleNext(this);
        updateWifiLabel();
        updateInputsButton();
        startWallpaperRotation();
        handler.postDelayed(minuteTick, MINUTE_MS);
        handler.removeCallbacks(cacheClean);
        handler.postDelayed(cacheClean, CacheCleaner.IDLE_MS);
        hiddenSinceResume = false;
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        long started = android.os.SystemClock.uptimeMillis();
        boolean rebuilt = reloadTiles();
        // Nothing installed or removed: Home comes back exactly as it was left, with no rebuild and
        // no shelf, glow or selection fading back in.
        if (restoreFocus && rebuilt) {
            glassShown = false;
            glider.hideNow();
            heroShown = false;
            heroShelf.setAlpha(0f);
            ambientShown = false;
            ambient.setAlpha(0f);
            syncGlassGlow();
        }
        android.util.Log.i("TvHome.Perf", "tiles " + (rebuilt ? "rebuilt" : "unchanged") + " in "
                + (android.os.SystemClock.uptimeMillis() - started) + " ms");
        // The tiles rise into place on the first launch only. Replayed on every return from an app,
        // it showed the glass tray empty for a moment and held Home back while the tiles arrived.
        if (animate && !reducedMotion && !tilesAnimatedOnce) grid.scheduleLayoutAnimation();
        if (animate) tilesAnimatedOnce = true;

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private boolean reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        Set<String> allHidden = adapter.hiddenPackages();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, allHidden, Cards.load(Cards.storeFile(this))), none) : none;
        boolean rebuilt = adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        return rebuilt;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            resuming = false;
            View now = getCurrentFocus();
            if (now != null && isTopPill(now)) moveTopHighlight(now);
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(packageReceiver);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(wallTick);
        if (azan != null) azan.stop();
        Azan.homeResumed = false;
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
        closeMenu(false);
        finishMove();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            syncHomeWallpaperSource();
            startWallpaperRotation();
            showCardPrompt();
        }
    }

    /**
     * Android is short of memory while Home sits behind another app: give back what can be remade,
     * so the launcher is less likely to be reclaimed and Home comes back without starting over.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) Backdrop.trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (focusSoundPool != null) {
            focusSoundPool.release();
            focusSoundPool = null;
            focusSoundLoaded = false;
        }
        wallDecoder.shutdownNow();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    /** Reused by every focus move. */
    private final Rect tileRect = new Rect();

    @Override
    public void onTileFocused(int position, View card) {
        if (lastSoundFocus >= 0 && lastSoundFocus != position && !resuming) playFocusSound();
        lastSoundFocus = position;
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // The rim is drawn inset by pad, right on the zoomed tile, so its corners use the tile's radius as is.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE);
        }
        glassTargetX = x;
        glassTargetY = y;
        // Exactly the zoomed card, behind it: at rest the app hides it completely, so it never lies
        // over an app and only shows while it travels between places.
        float cardRadius = AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE;
        if (gliderOnPill) {
            // Down from the top menu it travels dark, and turns to glass once it lands, unseen behind
            // the app.
            glider.moveTo(x + pad, y + pad, x + w - pad, y + h - pad, cardRadius);
            glider.onArrive(() -> {
                if (!gliderOnPill) glider.setStyle(Glider.GLASS);
            });
        } else {
            // From app to app it is a pane of glass gliding behind them.
            glider.setStyle(Glider.GLASS);
            glider.moveTo(x + pad, y + pad, x + w - pad, y + h - pad, cardRadius);
        }
        gliderOnPill = false;
        glassShown = true;
        // Every app belongs to the same horizontal row; focusTile handles revealing it with one
        // shared left/right scroll animation before focus arrives.
        if (placeName != null) hideShelf();
        else showHero(position, r.top);
        showAmbient(adapter.ambientColorAt(position));
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        // Internet wallpaper captions stay off Home; the focused app name is the shelf fallback.
        final String label = adapter.labelAt(position);
        final String where = "";
        int color = adapter.ambientColorAt(position);
        heroBottom = dockTopInRoot - HERO_GAP_DP * density;
        float y = heroBottom - heroShelf.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroShelf.animate().cancel();
            setHeroText(label, where);
            heroShelf.setY(y);
            heroShelf.setTranslationX(0f);
            heroShelf.setAlpha(1f);
            showAmbient(color);
            heroShown = true;
            return;
        }

        if (!heroShown) {
            setHeroText(label, where);
            heroShelf.setY(y);
            heroShelf.setTranslationX(0f);
            // Coming back to Home, the words are simply there with the picture: faded in after the
            // first frame, they arrived a beat behind everything else.
            heroShelf.animate().cancel();
            if (firstFrameHeld || resuming) heroShelf.setAlpha(1f);
            else heroShelf.animate().alpha(1f).setDuration(260).start();
            showAmbient(color);
            heroShown = true;
        } else if (changed) {
            heroShelf.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                setHeroText(label, where);
                heroShelf.setY(y);
                heroShelf.setTranslationX(12 * density);
                heroShelf.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            showAmbient(color);
        } else {
            // Same words, different app: every dock app shares the place's name, so the colour has
            // to follow focus on its own rather than ride along with a change of text.
            heroShelf.setY(y);
            showAmbient(color);
        }
    }

    /**
     * The one focus shape (Glider) goes behind everything it marks; the pills' glass makes way for it
     * as it arrives, and the dock tray paints it over its own glass.
     */
    private void setUpGlider() {
        glider = new Glider(this, reducedMotion);
        // Behind the shelf's text, the pills and the tiles, so it never draws over any of them.
        root.addView(glider, root.indexOfChild(heroShelf), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The glider replaces the glass selection outright.
        glass.setVisibility(View.GONE);
    }

    private boolean isTopPill(View v) {
        int id = v.getId();
        return id == R.id.btnInputs || id == R.id.btnWifi || id == R.id.btnHome || id == R.id.btnSettings;
    }

    /** Sends the focus shape onto a pill: from a tile it glides up and narrows into the pill. */
    private void moveTopHighlight(View pill) {
        if (resuming) return;
        pill.getLocationInWindow(pillAt);
        glider.getLocationInWindow(rootAt);
        float l = pillAt[0] - rootAt[0], t = pillAt[1] - rootAt[1];
        // The pills do not scroll with the grid, so the shape stops following the grid's scroll.
        glassShown = false;
        // Up to the top menu it is dark at once, and travels that way.
        gliderOnPill = true;
        glider.setStyle(Glider.DARK);
        glider.moveTo(l, t, l + pill.getWidth(), t + pill.getHeight(), pill.getHeight() / 2f);
    }

    /** The shelf shows the focused app name; photo captions stay hidden on Home. */
    private void setHeroText(String title, String where) {
        heroName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSizeSp(title));
        heroName.setText(title);
        heroWhere.setText(where);
        heroWhere.setVisibility(where.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Shouting titles are calmed down: "THE RIBBON HERO" becomes "The Ribbon Hero". A title that
     * already has lower case is left exactly as its studio wrote it, so "LEGO Star Wars" and
     * "WALL·E" keep their capitals instead of being flattened to "Lego" and "Wall·e".
     */
    private static String titleCase(String title) {
        if (title == null || title.isEmpty()) return title;
        for (int i = 0; i < title.length(); i++) {
            if (Character.isLowerCase(title.charAt(i))) return title;
        }
        StringBuilder out = new StringBuilder(title.length());
        boolean start = true;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            // A new word begins after a space or any of the marks a title tends to use.
            start = c == ' ' || c == '-' || c == ':' || c == '.' || c == '\'' || c == '(' || c == '/';
        }
        return out.toString();
    }

    /** The largest size at which the caption wraps into at most two lines without being cut off. */
    private float titleSizeSp(String title) {
        if (title == null || title.isEmpty() || heroNameMaxWidth <= 0) return HERO_TITLE_MAX_SP;
        android.text.TextPaint paint = new android.text.TextPaint(heroName.getPaint());
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        for (float sp = HERO_TITLE_MAX_SP; sp > HERO_TITLE_MIN_SP; sp -= 2f) {
            paint.setTextSize(sp * scaled);
            android.text.StaticLayout layout = android.text.StaticLayout.Builder
                    .obtain(title, 0, title.length(), paint, heroNameMaxWidth)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build();
            int lastLine = layout.getLineCount() - 1;
            if (lastLine >= 0 && layout.getEllipsisCount(lastLine) == 0) return sp;
        }
        return HERO_TITLE_MIN_SP;
    }

    /** Tracks the current wallpaper title so Home can keep its photo caption hidden. */
    private void setPlace(HomeWallpapers.Photo photo) {
        String title = photo == null ? null : titleCase(photo.displayTitle());
        if (title == null ? placeName == null : title.equals(placeName)) return;
        placeName = title;
        // The photo name is intentionally omitted from Home; keep the focused app tile as its label.
        if (title != null) hideShelf();
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture; changing apps only uploads it once.
     */
    private void setAmbient(int color) {
        setAmbient(color, false);
    }

    private FadeTopFrame gridWrap;

    /** Turns the top fade on while any row reaches into its band, with room for a focused tile's zoom. */
    private void updateTopFade() {
        if (gridWrap == null) return;
        float band = 56 * density + 24 * density;
        boolean inBand = false;
        for (int i = 0; i < grid.getChildCount() && !inBand; i++) {
            View child = grid.getChildAt(i);
            inBand = grid.getTop() + child.getTop() + child.getTranslationY() < band;
        }
        gridWrap.setFadeStrength(inBand ? 1f : 0f);
    }

    /** Keeps the horizontal app row near the bottom edge and says whether it was already positioned. */
    private boolean parkDock() {
        int h = grid.getHeight();
        if (h <= 0) return false;
        // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
        int inner = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int tileH = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density));
        int itemH = AppAdapter.itemHeightPx(tileH, density);
        int dockRow = itemH + Math.round((6 + 12) * density);
        // Anchor the visible tile near the bottom, leaving room for its focus scale and shadow.
        int desiredTop = h - dockRow + Math.round(25 * density);
        int cardTop = Math.round((6 + 8) * density); // decoration inset plus item_app's top padding
        int maxTop = Math.max(0, h - tileH - cardTop - Math.round(24 * density));
        int want = Math.max(0, Math.min(desiredTop, maxTop));
        if (grid.getPaddingTop() == want) return true;
        grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
        return false;
    }

    /**
     * Home's first frame waits until Home is whole: tiles bound and laid out, the dock parked at the
     * bottom and the wallpaper in. Drawn as soon as the window was ready, it showed the glass tray
     * floating empty near the top, then the tiles, then the dock dropping into place. This matters
     * most after a long stay in another app, when Android has reclaimed the launcher and Home starts
     * from nothing. The wait is capped, so Home can never hang on a slow picture.
     */
    private void holdFirstFrame() {
        final long start = android.os.SystemClock.uptimeMillis();
        firstFrameHeld = true;
        final boolean expectsWallpaper = config != null && config.masjidHome && hasValidatedInternet();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                // Parked here rather than by the layout listener alone: while frames are held, the
                // second layout pass that listener relies on does not always come.
                boolean parked = parkDock();
                // Hold until the wallpaper and app grid are ready; dock app names are drawn on their tiles.
                boolean whole = adapter.getItemCount() > 0 && grid.getChildCount() > 0 && parked
                        && (wallBitmap != null || !expectsWallpaper) && (heroShown || lastFocused >= 0);
                if (!whole && android.os.SystemClock.uptimeMillis() - start < FIRST_FRAME_WAIT_MS) {
                    return false;   // skip this frame; the next traversal asks again
                }
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                firstFrameHeld = false;
                android.util.Log.i("TvHome.Perf", "first frame after " + (android.os.SystemClock.uptimeMillis() - start)
                        + " ms" + (whole ? "" : " (gave up waiting: tiles=" + adapter.getItemCount()
                        + " laidOut=" + grid.getChildCount() + " dockParked=" + parked
                        + " wallpaper=" + (wallBitmap != null) + ")"));
                return true;
            }
        });
    }

    /** Tells the glass the glow's colour and strength, so what shows through matches around it. */
    private void syncGlassGlow() {
        Backdrop.setGlow(ambientShape(), glowFrom, ambientColor, glowMix, ambient.getAlpha());
    }

    /** Runs the glass through the same colour blend as the room's TransitionDrawable. */
    private void blendGlassGlow(int from, long ms) {
        if (glowBlend != null) glowBlend.cancel();
        glowFrom = from;
        glowMix = 0f;
        glowBlend = android.animation.ValueAnimator.ofFloat(0f, 1f);
        glowBlend.setDuration(ms);
        glowBlend.addUpdateListener(a -> {
            glowMix = (float) a.getAnimatedValue();
            syncGlassGlow();
        });
        glowBlend.start();
    }

    /** The glow's shape in white, drawn on first use and kept: a tint gives it each app's colour. */
    private Bitmap ambientShape() {
        if (ambientShape != null) return ambientShape;
        int w = 480;
        int h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(
                w * 0.32f, h * 0.42f, w * 0.62f,
                new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        new Canvas(bmp).drawRect(0, 0, w, h, paint);
        ambientShape = bmp;
        return bmp;
    }

    /** With crossFade, the old glow fades into the new one (two small textures, one blend). */
    private void setAmbient(int color, boolean crossFade) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        android.graphics.drawable.Drawable old = ambient.getBackground();
        if (old instanceof android.graphics.drawable.TransitionDrawable) {
            android.graphics.drawable.TransitionDrawable t = (android.graphics.drawable.TransitionDrawable) old;
            old = t.getDrawable(t.getNumberOfLayers() - 1);   // a blend still running: start from where it was heading
        }
        int previous = ambientColor;
        ambientColor = color;
        // Every app's glow is the same shape in a different colour, so the shape is drawn once and
        // each app only tints it. Drawing a fresh bitmap per move cost a CPU fill, half a megabyte
        // and a texture upload every time focus moved, which is what made moving around feel heavy
        // once every tile, not only the dock, tinted the room.
        BitmapDrawable drawable = new BitmapDrawable(getResources(), ambientShape());
        drawable.setGravity(Gravity.FILL);
        drawable.setColorFilter(new android.graphics.PorterDuffColorFilter(
                0xFF000000 | color, android.graphics.PorterDuff.Mode.SRC_IN));
        if (crossFade && old != null) {
            android.graphics.drawable.TransitionDrawable t = new android.graphics.drawable.TransitionDrawable(
                    new android.graphics.drawable.Drawable[]{old, drawable});
            t.setCrossFadeEnabled(true);
            ambient.setBackground(t);
            t.startTransition(450);
            // The glass blends with the room, over the same 450 ms, instead of jumping ahead of it.
            blendGlassGlow(previous, 450);
        } else {
            ambient.setBackground(drawable);
            if (glowBlend != null) glowBlend.cancel();
            glowFrom = color;
            glowMix = 1f;
            syncGlassGlow();
        }
    }

    /** Focus has left the apps altogether: both the shelf and the glow go. */
    private void hideHero() {
        hideShelf();
        if (!ambientShown) return;
        ambientShown = false;
        ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300)
                .setUpdateListener(a -> syncGlassGlow()).start();
    }

    private void hideShelf() {
        if (!heroShown) return;
        heroShown = false;
        heroShelf.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
    }

    /**
     * Lights the room in this app's colour. From one app to the next it blends straight across,
     * with no dip to plain in between, whether the move is along the dock, down into the grid or
     * back up again.
     */
    private void showAmbient(int color) {
        if (!ambientShown) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(reducedMotion ? 160 : 600)
                    .setUpdateListener(a -> syncGlassGlow()).start();
            ambientShown = true;
        } else if (color != ambientColor) {
            setAmbient(color, !reducedMotion);
        }
    }

    private void hideGlass() {
        glassShown = false;
    }

    // ---- navigation ------------------------------------------------------------------------------

    // ---- arranging the tiles ---------------------------------------------------------------------

    /** The tile being moved (adapter position), or -1; its card wiggles while it is. */
    private int movingPos = -1;
    private View movingCard;
    private android.animation.ObjectAnimator wiggle;
    /** A key whose release is swallowed: the OK or Back that put a moved tile down must not also open it. */
    private int swallowRelease = -1;
    /**
     * Slides the tiles into their new places while an app is moved. The grid has no item animations
     * otherwise: they would replay on every rebuild and cost frames while scrolling.
     */
    private androidx.recyclerview.widget.DefaultItemAnimator moveSlide;

    /** Holding OK on an app: a glass menu to move or hide it, or to open Android's page for it. */
    @Override
    public boolean onTileHeld(int position, View card) {
        if (openMenu != null || movingPos >= 0) return true;
        FrameLayout panel = (FrameLayout) getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final String key = adapter.keyAt(position);
        final String pkg = adapter.packageAt(position);
        if (adapter.canMove(position)) {
            addInputRow(rows, () -> {
                // No selection gliding back from the menu: it is simply gone, and the app wiggles.
                glider.hideNow();
                gliderOnPill = false;
                closeMenu(true);
            }, R.drawable.ic_move, getString(R.string.tile_move), -1, () -> startMove(key));
        }
        if (adapter.canHide(position)) {
            addInputRow(rows, () -> closeMenu(true), R.drawable.ic_lock, getString(R.string.tile_hide_app), -1, () -> {
                if (adapter.hidePackage(pkg)) {
                    reloadApps(false);
                    Toast.makeText(this, getString(R.string.app_hidden, adapter.labelForPackage(pkg)), Toast.LENGTH_SHORT).show();
                }
            });
        }
        addInputRow(rows, () -> closeMenu(false), R.drawable.ic_info, getString(R.string.tile_app_info), -1,
                () -> adapter.openAppInfo(adapter.positionOf(key)));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = Math.max((int) (190 * density), panel.getMeasuredWidth());
        // Under the app when there is room; over it in the dock, which sits near the bottom.
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        card.getLocationInWindow(menuAt);
        root.getLocationInWindow(menuGliderAt);
        int below = menuAt[1] - menuGliderAt[1] + card.getHeight() + (int) (18 * density) + panel.getMeasuredHeight();
        showMenu(panel, rows, card, width, below > root.getHeight() - (int) (24 * density));
        return true;
    }

    /** The Settings long-press menu includes app visibility even when module cards are turned off. */
    private void showLauncherOptions(View anchor) {
        if (openMenu != null) return;
        FrameLayout panel = (FrameLayout) getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        Runnable close = () -> closeMenu(true);
        if (moduleCards) {
            addInputRow(rows, close, R.drawable.ic_settings, getString(R.string.module_cards), -1, this::showModuleCards);
        }
        addInputRow(rows, close, R.drawable.ic_info, getString(R.string.hidden_apps), -1, this::showHiddenApps);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = Math.max((int) (210 * density), panel.getMeasuredWidth());
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        anchor.getLocationInWindow(menuAt);
        root.getLocationInWindow(menuGliderAt);
        int below = menuAt[1] - menuGliderAt[1] + anchor.getHeight() + (int) (18 * density) + panel.getMeasuredHeight();
        showMenu(panel, rows, anchor, width, below > root.getHeight() - (int) (24 * density));
    }

    /** A list of apps hidden by the user; selecting one restores its tile without reinstalling it. */
    private void showHiddenApps() {
        List<String> hidden = adapter.userHiddenPackages();
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.no_hidden_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        View anchor = findViewById(R.id.btnSettings);
        FrameLayout panel = (FrameLayout) getLayoutInflater().inflate(R.layout.popup_hidden_apps, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        android.widget.ScrollView list = panel.findViewById(R.id.hiddenScroll);
        for (String pkg : hidden) {
            String label = adapter.labelForPackage(pkg);
            addInputRow(rows, () -> closeMenu(true), R.drawable.ic_info, getString(R.string.show_hidden_app, label), -1, () -> {
                if (adapter.showPackage(pkg)) {
                    reloadApps(false);
                    Toast.makeText(this, getString(R.string.app_shown, label), Toast.LENGTH_SHORT).show();
                }
            });
        }
        rows.measure(View.MeasureSpec.makeMeasureSpec((int) (270 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int maxListHeight = Math.max((int) (120 * density), root.getHeight() - (int) (190 * density));
        list.getLayoutParams().height = Math.min(rows.getMeasuredHeight(), maxListHeight);
        panel.measure(View.MeasureSpec.makeMeasureSpec((int) (270 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        anchor.getLocationInWindow(menuAt);
        root.getLocationInWindow(menuGliderAt);
        int below = menuAt[1] - menuGliderAt[1] + anchor.getHeight() + (int) (18 * density) + panel.getMeasuredHeight();
        showMenu(panel, rows, anchor, (int) (270 * density), below > root.getHeight() - (int) (24 * density));
    }

    /** Picks the app up: it wiggles, the arrows carry it, and OK or Back puts it down. */
    private void startMove(String key) {
        int pos = adapter.positionOf(key);
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh == null || !adapter.canMove(pos)) return;
        movingPos = pos;
        movingCard = vh.itemView.findViewById(R.id.card);
        movingCard.requestFocus();
        glider.setPaneHidden(true);
        if (moveSlide == null) {
            moveSlide = new androidx.recyclerview.widget.DefaultItemAnimator();
            moveSlide.setSupportsChangeAnimations(false);
            moveSlide.setMoveDuration(reducedMotion ? 140 : 220);
        }
        grid.setItemAnimator(moveSlide);
        float tilt = reducedMotion ? 0.8f : 1.4f;
        wiggle = android.animation.ObjectAnimator.ofFloat(movingCard, View.ROTATION, -tilt, tilt);
        wiggle.setDuration(reducedMotion ? 260 : 140);
        wiggle.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wiggle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wiggle.start();
    }

    /** Puts the moving app down where it is; the order was kept as it moved. */
    private void finishMove() {
        if (movingPos < 0) return;
        if (wiggle != null) wiggle.cancel();
        wiggle = null;
        if (movingCard != null) movingCard.animate().rotation(0f).setDuration(120).start();
        movingCard = null;
        movingPos = -1;
        glider.setPaneHidden(false);
        // Back to no item animations once the last slide has finished.
        RecyclerView.ItemAnimator slide = grid.getItemAnimator();
        if (slide != null) slide.isRunning(() -> {
            if (movingPos < 0 && grid.getItemAnimator() == slide) grid.setItemAnimator(null);
        });
    }

    /** Carries the moving app one step, the other tiles making room, and the selection follows it. */
    private void stepMove(int keyCode) {
        int last = adapter.lastMovable();
        int to = movingPos;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (movingPos > 0) to = movingPos - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (movingPos < last) to = movingPos + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            default:
                return;
        }
        if (!adapter.move(movingPos, to)) return;
        movingPos = to;
        final View card = movingCard;
        final int pos = to;
        // Once the tiles have shifted, glide the selection (and the shelf, and the scroll) to it.
        grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                grid.removeOnLayoutChangeListener(this);
                grid.post(() -> {
                    if (movingPos == pos && card != null && card.isAttachedToWindow()) focusTile(pos);
                });
            }
        });
        grid.requestLayout();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        // Any button ends the azan; the press does nothing else.
        if (azan != null && azan.showing() && event.getAction() == KeyEvent.ACTION_DOWN) {
            azan.stop();
            swallowRelease = code;
            return true;
        }
        if (code == swallowRelease && event.getAction() == KeyEvent.ACTION_UP) {
            swallowRelease = -1;
            return true;
        }
        if (movingPos < 0) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) stepMove(code);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) {
                    finishMove();
                    swallowRelease = code;
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        // All apps share one horizontal row; left and right traverse the whole list.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (position <= 0) return true;
            focusTile(position - 1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (position >= count - 1) return true;
            focusTile(position + 1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            return false; // let focus return to the top bar
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN; // only one app row
    }

    /** The tile {@link #focusWhenSettled} focuses once the grid stops scrolling to it. */
    private int pendingFocus;
    private final RecyclerView.OnScrollListener focusWhenSettled = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
            if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
            rv.removeOnScrollListener(this);
            RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pendingFocus);
            View target = h != null ? h.itemView.findViewById(R.id.card) : null;
            if (target == null) return;
            if (target.isFocused()) onTileFocused(pendingFocus, target);
            else target.requestFocus();
        }
    };

    /** Checks the focused tile and its glass rim against the horizontal viewport. */
    private boolean needsHorizontalReveal(View item, View card) {
        int edge = Math.round(card.getWidth() * (AppAdapter.FOCUS_SCALE - 1f) / 2f + 6 * density);
        int left = item.getLeft() + card.getLeft() - edge;
        int right = item.getLeft() + card.getRight() + edge;
        return left < grid.getPaddingLeft() || right > grid.getWidth() - grid.getPaddingRight();
    }

    /** Uses one snap-to-nearest-edge animation for partially visible and off-screen apps. */
    private void smoothScrollToTile(int position) {
        pendingFocus = position;
        grid.removeOnScrollListener(focusWhenSettled);
        grid.addOnScrollListener(focusWhenSettled);
        LinearSmoothScroller scroller = new LinearSmoothScroller(this) {
            @Override protected int getHorizontalSnapPreference() { return SNAP_TO_ANY; }
        };
        scroller.setTargetPosition(position);
        grid.getLayoutManager().startSmoothScroll(scroller);
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            (btnWifi.getVisibility() == View.VISIBLE ? btnWifi : findViewById(R.id.btnSettings)).requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            if (card != null && !needsHorizontalReveal(vh.itemView, card)) {
                // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
                if (card.isFocused()) onTileFocused(pos, card);
                else card.requestFocus();
                return;
            }
        }
        // Reveal the target, then focus after scrolling settles. This is the same in either direction.
        smoothScrollToTile(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateShamsiDate() {
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    private void updateHeaderDate() {
        TextView date = findViewById(R.id.date);
        if (date != null) date.setText(new java.text.SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("id", "ID"))
                .format(new java.util.Date()));
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAutoLocation() {
        if (!autoLocation && !showWeather) return;
        if (!hasLocationPermission()) {
            android.content.SharedPreferences p = getSharedPreferences("location", MODE_PRIVATE);
            if (!p.getBoolean("permission_prompted", false)) {
                p.edit().putBoolean("permission_prompted", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION}, 70);
            }
            return;
        }
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        readLastKnownLocation();
        if (locationManager == null || locationUpdatesStarted) return;
        boolean registered = false;
        for (String provider : locationManager.getProviders(true)) {
            if (LocationManager.NETWORK_PROVIDER.equals(provider) || LocationManager.GPS_PROVIDER.equals(provider)
                    || LocationManager.PASSIVE_PROVIDER.equals(provider)) {
                try {
                    locationManager.requestLocationUpdates(provider, 10L * 60 * 1000, 1000f, locationListener, Looper.getMainLooper());
                    registered = true;
                } catch (SecurityException e) {
                    android.util.Log.w("TvHome.Location", "location permission is unavailable");
                    return;
                } catch (IllegalArgumentException e) {
                    android.util.Log.w("TvHome.Location", "provider unavailable: " + provider);
                }
            }
        }
        locationUpdatesStarted = registered;
    }

    private void stopLocationUpdates() {
        if (!locationUpdatesStarted || locationManager == null) return;
        try { locationManager.removeUpdates(locationListener); }
        catch (SecurityException ignored) { }
        locationUpdatesStarted = false;
    }

    private void readLastKnownLocation() {
        if (locationManager == null || !hasLocationPermission()) return;
        try {
            Location best = null;
            for (String provider : locationManager.getProviders(true)) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate == null || System.currentTimeMillis() - candidate.getTime() > 12L * 60 * 60 * 1000) continue;
                if (best == null || (candidate.hasAccuracy() && (!best.hasAccuracy() || candidate.getAccuracy() < best.getAccuracy()))) best = candidate;
            }
            if (best != null) acceptLocation(best);
        } catch (SecurityException e) {
            android.util.Log.w("TvHome.Location", "location permission is unavailable");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 70 && hasLocationPermission()) startAutoLocation();
    }

    private void acceptLocation(Location location) {
        if (location == null) return;
        boolean moved = latestLocation == null || latestLocation.distanceTo(location) >= 1000f;
        latestLocation = new Location(location);
        if (moved) updateWeather();
        if (!autoLocation) return;
        PrayerTimes.City city = PrayerTimes.currentCity(this);
        boolean cityChanged = city == null || lastPrayerCityLocation == null || lastPrayerCityLocation.distanceTo(location) >= 10_000f;
        long retryDelay = city == null ? 30L * 60 * 1000 : 5L * 60 * 1000;
        if (!cityChanged || prayerCityResolveRunning || System.currentTimeMillis() - lastPrayerCityAttempt < retryDelay) return;
        prayerCityResolveRunning = true;
        lastPrayerCityAttempt = System.currentTimeMillis();
        Location snapshot = new Location(location);
        new Thread(() -> {
            PrayerTimes.City found = PrayerTimes.resolveCity(getApplicationContext(), snapshot);
            handler.post(() -> {
                prayerCityResolveRunning = false;
                if (found != null) {
                    lastPrayerCityLocation = snapshot;
                    AzanAlarm.scheduleNext(getApplicationContext());
                    updatePrayerTimes();
                    updateWeather();
                }
            });
        }, "prayer-city").start();
    }

    private void updateWeather() {
        if (!showWeather || weatherLine == null || latestLocation == null) return;
        Weather.Conditions cached = Weather.cached(this);
        if (cached != null) {
            PrayerTimes.City city = PrayerTimes.currentCity(this);
            showWeather(cached, city != null ? city.label() : "");
        }
        if (weatherRefreshRunning || !Weather.due(this, latestLocation)) return;
        weatherRefreshRunning = true;
        Location snapshot = new Location(latestLocation);
        new Thread(() -> {
            Weather.Conditions current = Weather.fetch(snapshot.getLatitude(), snapshot.getLongitude());
            if (current != null) Weather.save(getApplicationContext(), snapshot, current);
            String label = PrayerTimes.displayLocation(getApplicationContext(), snapshot);
            handler.post(() -> {
                weatherRefreshRunning = false;
                if (current != null && !isDestroyed()) showWeather(current, label);
            });
        }, "weather").start();
    }

    private void showWeather(Weather.Conditions current, String location) {
        String text = current.text();
        if (location != null && !location.isEmpty()) text += "  ·  " + location;
        weatherLine.setText(text);
        weatherLine.setContentDescription("Cuaca dari Open-Meteo: " + text);
        weatherLine.setVisibility(View.VISIBLE);
    }

    /**
     * Starts the azan when a prayer time has come, if Home is what is on screen. Under the
     * screensaver Home has no window focus, and the screensaver gives the azan itself.
     */
    private void checkAzan() {
        if (azan == null || azan.showing() || !hasWindowFocus()) return;
        PrayerTimes.City city = PrayerTimes.currentCity(this);
        if (city == null) return;
        int due = Azan.takeDue(this, city.id);
        if (due >= 0) azan.start(this, city.id, due, azanReducedMotion);
    }

    /**
     * Today's five prayer times, resolved from the TV's current Android location.
     */
    private void updatePrayerTimes() {
        PrayerTimes.City city = PrayerTimes.currentCity(this);
        if (city == null) {
            prayerRow.setVisibility(View.GONE);
            if (autoLocation && latestLocation != null) acceptLocation(latestLocation);
            return;
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        String todayKey = PrayerTimes.key(now);
        PrayerTimes.Day today = PrayerTimes.day(this, city.id, todayKey);
        if (today == null) {
            prayerRow.setVisibility(View.GONE);
            if (!prayerRefreshRunning && PrayerTimes.due(this, city.id, todayKey)) {
                prayerRefreshRunning = true;
                final String cityId = city.id;
                new Thread(() -> {
                    boolean ok = PrayerTimes.refresh(getApplicationContext(), cityId, todayKey);
                    java.util.Calendar tomorrow = (java.util.Calendar) now.clone();
                    tomorrow.add(java.util.Calendar.DAY_OF_MONTH, 1);
                    String tomorrowKey = PrayerTimes.key(tomorrow);
                    if (PrayerTimes.due(getApplicationContext(), cityId, tomorrowKey))
                        PrayerTimes.refresh(getApplicationContext(), cityId, tomorrowKey);
                    handler.post(() -> {
                        prayerRefreshRunning = false;
                        if (ok) AzanAlarm.scheduleNext(getApplicationContext());
                        if (ok && !isDestroyed()) updatePrayerTimes();
                    });
                }, "prayer-times").start();
            }
            return;
        }
        int nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        int next = PrayerTimes.next(today.minutes, nowMin);
        int left;
        if (next >= 0) {
            left = today.minutes[next] - nowMin;
        } else {
            // After Isya, the next is tomorrow's Subuh.
            java.util.Calendar t = (java.util.Calendar) now.clone();
            t.add(java.util.Calendar.DAY_OF_MONTH, 1);
            PrayerTimes.Day tomorrow = PrayerTimes.day(this, city.id, PrayerTimes.key(t));
            left = 24 * 60 - nowMin + (tomorrow != null ? tomorrow : today).minutes[0];
        }
        java.util.Calendar tomorrowDate = (java.util.Calendar) now.clone();
        tomorrowDate.add(java.util.Calendar.DAY_OF_MONTH, 1);
        String tomorrowKey = PrayerTimes.key(tomorrowDate);
        if (PrayerTimes.day(this, city.id, tomorrowKey) == null && !prayerRefreshRunning
                && PrayerTimes.due(this, city.id, tomorrowKey)) {
            prayerRefreshRunning = true;
            new Thread(() -> {
                PrayerTimes.refresh(getApplicationContext(), city.id, tomorrowKey);
                handler.post(() -> {
                    prayerRefreshRunning = false;
                    AzanAlarm.scheduleNext(getApplicationContext());
                });
            }, "prayer-tomorrow").start();
        }
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        int bright = 0xFFFFFFFF;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        for (int i = 0; i < today.minutes.length; i++) {
            if (i > 0) b.append("    ");
            int start = b.length();
            b.append(PrayerTimes.NAMES[i]).append("  ").append(PrayerTimes.format(today.minutes[i], is24));
            if (i == next) {
                b.setSpan(new android.text.style.ForegroundColorSpan(bright), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        b.append("  │  ");
        int start = b.length();
        b.append(PrayerTimes.NAMES[next >= 0 ? next : 0]).append(" ").append(PrayerTimes.countdown(left));
        b.setSpan(new android.text.style.ForegroundColorSpan(bright), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        prayerRow.setText(b);
        prayerRow.setVisibility(View.VISIBLE);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        if (btnWifi.getVisibility() == View.GONE) return;   // show_network_pill off
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (config != null && !config.showInputs) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** Loads the short D-pad navigation tick before the app grid receives focus. */
    private void prepareFocusSound() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        focusSoundPool = new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(attributes)
                .build();
        focusSoundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (sampleId == focusSoundId && status == 0) focusSoundLoaded = true;
        });
        focusSoundId = focusSoundPool.load(this, R.raw.navigation_focus, 1);
    }

    /** Plays a short, quiet SoundPool tick when D-pad focus moves to another app. */
    private void playFocusSound() {
        SoundPool pool = focusSoundPool;
        if (pool == null || !focusSoundLoaded || focusSoundId == 0) return;
        pool.play(focusSoundId, 0.42f, 0.42f, 1, 0, 1f);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** The glass menu while one is open (Inputs, or an app's), and what it opened from. */
    private View openMenu;
    private View menuAnchor;
    private final int[] menuAt = new int[2], menuGliderAt = new int[2];

    /**
     * Menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. It is drawn in
     * Home's own window rather than a popup window of its own: a new window takes about a quarter of
     * a second to reach the screen on this TV, so the selection could not glide out of the pill into
     * it. Here Home's own focus shape glides from the pill to the first row, the way it glides into
     * the dock's tray, and the menu's glass paints it over itself.
     */
    private void showInputs() {
        if (openMenu != null) return;
        View anchor = btnInputs;
        FrameLayout panel = (FrameLayout) getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        Runnable close = () -> closeMenu(false);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, close, hdmi ? R.drawable.ic_hdmi : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, close, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null && config.showLiveTv) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, close, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        width = Math.max(width, panel.getMeasuredWidth());

        showMenu(panel, rows, anchor, width, false);
    }

    /**
     * Shows a glass menu in Home's own window, under {@code anchor} or, with {@code above}, over it:
     * Home's focus shape glides from the anchor to the first row, the menu's glass paints it over
     * itself, and the glass and rows fade in around it. Up from the first row (Down from the last when
     * the menu is above), or Back, closes it and the shape glides back to the anchor.
     */
    private void showMenu(FrameLayout panel, LinearLayout rows, View anchor, int width, boolean above) {
        GlassDrawable glass = new GlassDrawable(panel, 20 * density, density, glider, 0.5f, true);
        panel.setBackground(glass);
        Backdrop.watch(panel);
        glider.addCompanion(panel);
        anchor.getLocationInWindow(menuAt);
        root.getLocationInWindow(menuGliderAt);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.max(0, Math.min(menuAt[0] - menuGliderAt[0], root.getWidth() - width));
        int gap = (int) (10 * density);
        if (above) {
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            lp.topMargin = Math.max(0, menuAt[1] - menuGliderAt[1] - gap - panel.getMeasuredHeight());
        } else {
            lp.topMargin = menuAt[1] - menuGliderAt[1] + anchor.getHeight() + gap;
        }
        root.addView(panel, lp);
        openMenu = panel;
        menuAnchor = anchor;

        // Focus stays in the menu until it closes. Leaving it towards the anchor closes it, and the
        // selection glides back into what it opened from.
        View first = null, last = null;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (!row.isFocusable()) continue;
            if (first == null) first = row;
            last = row;
            row.setOnFocusChangeListener((v, has) -> {
                if (has) glideToMenuRow(panel, rows, v);
            });
        }
        final View top = first, bottom = last;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (!row.isFocusable()) continue;
            row.setOnKeyListener((v, keyCode, event) -> {
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (v != top) return false;
                        if (down && !above) closeMenu(true);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (v != bottom) return false;
                        if (down && above) closeMenu(true);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                        if (down) closeMenu(true);
                        return true;
                    default:
                        return false;
                }
            });
        }

        // The glass and the rows fade in around the selection as it arrives.
        glass.setAlpha(0);
        rows.setAlpha(0f);
        final float slide = (above ? 8 : -8) * density;
        rows.setTranslationY(slide);
        android.animation.ValueAnimator in = android.animation.ValueAnimator.ofFloat(0f, 1f);
        in.setDuration(220);
        in.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        in.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            glass.setAlpha(Math.round(255 * f));
            rows.setAlpha(f);
            rows.setTranslationY(slide * (1f - f));
        });
        in.start();
        if (top != null) top.requestFocus();
    }

    /**
     * Glides Home's focus shape onto a row of the Inputs menu. From layout positions, not on-screen
     * ones: the rows slide into place as the menu opens, and the shape must land where the row ends
     * up, not where it was when focus arrived. The first row takes focus before the menu has been
     * laid out, so then it waits for the layout.
     */
    private void glideToMenuRow(View panel, View rows, View row) {
        if (!panel.isLaidOut() || row.getHeight() == 0) {
            panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    panel.removeOnLayoutChangeListener(this);
                    // Still inside the layout pass here, where the panel does not count as laid
                    // out yet: glide once the pass is over.
                    panel.post(() -> {
                        if (row.hasFocus()) glideToMenuRow(panel, rows, row);
                    });
                }
            });
            return;
        }
        row.getLocationInWindow(menuAt);
        root.getLocationInWindow(menuGliderAt);
        float l = menuAt[0] - menuGliderAt[0];
        float t = menuAt[1] - menuGliderAt[1];
        gliderOnPill = true;
        glider.setStyle(Glider.DARK);
        glider.moveTo(l, t, l + row.getWidth(), t + row.getHeight(), 13 * density);
    }

    /** Closes the open menu; with {@code toAnchor}, focus and the selection go back to what it opened from. */
    private void closeMenu(boolean toAnchor) {
        View panel = openMenu;
        if (panel == null) return;
        openMenu = null;
        if (toAnchor && menuAnchor != null) menuAnchor.requestFocus();
        menuAnchor = null;
        panel.animate().alpha(0f).setDuration(reducedMotion ? 80 : 150).withEndAction(() -> {
            glider.removeCompanion(panel);
            root.removeView(panel);
        }).start();
    }

    private boolean inMenu(View v) {
        for (View p = v; p != null && openMenu != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == openMenu) return true;
        }
        return false;
    }

    private PopupWindow glassPopup(View panel, int width) {
        // The same glass as the pills and the tray: what is behind it, softened, with a shining edge.
        panel.setBackground(new GlassDrawable(panel, 20 * density, density, null, 0.5f));
        Backdrop.watch(panel);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, Runnable close, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            close.run();
            action.run();
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
