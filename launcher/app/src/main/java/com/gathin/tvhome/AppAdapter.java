package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical. Once anyone has
 * moved a tile (hold OK, then Move), the order they made is kept instead, and apps installed later
 * join at the end.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** OK was held on a tile. Return false to open the app's info page, as before. */
        boolean onTileHeld(int position, View card);
    }

    static final class Entry {
        static final int APP = 0, CARD = 1;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.07f;
    /** Shared by every tile's zoom, so a focus move makes no new objects. */
    private static final DecelerateInterpolator ZOOM_REDUCED = new DecelerateInterpolator();
    private static final OvershootInterpolator ZOOM_IN = new OvershootInterpolator(1.6f);
    private static final OvershootInterpolator ZOOM_OUT = new OvershootInterpolator(0f);
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final Set<String> configuredHidden;
    private final Set<String> userHidden;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final List<Entry> items = new ArrayList<>();
    /** The order someone arranged the tiles in, by key (see {@link #keyAt}); empty until they do. */
    private final List<String> arranged = new ArrayList<>();
    private static final String ORDER_PREFS = "tiles", ORDER_KEY = "order";
    private static final String VISIBILITY_PREFS = "app_visibility", HIDDEN_KEY = "hidden_packages";

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.configuredHidden = new HashSet<>(cfg.hiddenApps);
        Set<String> savedHidden = ctx.getSharedPreferences(VISIBILITY_PREFS, Context.MODE_PRIVATE)
                .getStringSet(HIDDEN_KEY, Collections.emptySet());
        this.userHidden = new HashSet<>(savedHidden);
        this.hidden = new HashSet<>(configuredHidden);
        this.hidden.addAll(userHidden);
        this.reducedMotion = cfg.reducedMotion;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
        String saved = ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).getString(ORDER_KEY, "");
        if (!saved.isEmpty()) arranged.addAll(Arrays.asList(saved.split("\n")));
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    /** What the tiles were last built from; the same list next time means there is nothing to redo. */
    private String builtFrom;
    /**
     * The app tiles of the last build, by component and package update time: a rebuild (one app
     * updated, installed or removed) loads only what changed, and the rest keep their artwork and
     * sampled glow.
     */
    private Map<String, Entry> builtApps = new HashMap<>();

    /**
     * Rebuilds the tiles, unless the launchable apps, their installed versions and the cards are
     * exactly what the tiles were last built from. Loading every banner, icon and label is most of
     * what coming back to Home costs, it runs before Home can draw, and almost every return finds
     * nothing changed.
     *
     * @return whether the tiles were rebuilt
     */
    boolean reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
        // A phone-only app named in dock_apps (e.g. the phone Play Store some boxes ship) has no leanback
        // entry; take its ordinary launcher activity instead. Apps not named stay hidden.
        Set<String> listed = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) listed.add(ri.activityInfo.packageName);
        for (String pkg : favourites) {
            if (listed.contains(pkg)) continue;
            Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> more = pm.queryIntentActivities(phone, 0);
            if (!more.isEmpty() && more.get(0).activityInfo != null) {
                found.add(more.get(0));
                listed.add(pkg);
            }
        }
        // Android often keeps the same install path across app updates, so include lastUpdateTime
        // to refresh artwork when an app is upgraded in place. Cache it per package because an app
        // can expose more than one launchable activity.
        List<String> parts = new ArrayList<>();
        Map<String, Long> updateTimes = new HashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            long updated = updateTimes.computeIfAbsent(pkg, name -> {
                try {
                    return pm.getPackageInfo(name, 0).lastUpdateTime;
                } catch (PackageManager.NameNotFoundException e) {
                    return 0L;
                }
            });
            parts.add(pkg + '/' + ri.activityInfo.name + '@'
                    + ri.activityInfo.applicationInfo.sourceDir + ':' + updated);
        }
        for (Cards.Card c : cards) {
            parts.add('#' + c.pkg + '/' + c.component.flattenToShortString() + '|' + c.label + '|'
                    + c.caption + '|' + c.accent);
        }
        Collections.sort(parts);
        String signature = android.text.TextUtils.join(";", parts);
        if (signature.equals(builtFrom) && !items.isEmpty()) return false;
        builtFrom = signature;
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        Map<String, Entry> apps = new HashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            long updated = updateTimes.getOrDefault(pkg, 0L);
            String key = component.flattenToShortString() + '@' + ri.activityInfo.applicationInfo.sourceDir
                    + ':' + updated;
            Entry built = card == null ? builtApps.get(key) : null;
            if (built != null) {
                fresh.add(built);
                apps.put(key, built);
                continue;
            }
            Drawable banner = loadBanner(pm, ri);
            // The icon only shows on a tile without a banner.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
                // redraw the tile name as something else (#117).
                Entry entry = new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon);
                fresh.add(entry);
                apps.put(key, entry);
            }
        }
        builtApps = apps;
        Map<String, Integer> place = new HashMap<>();
        for (int i = 0; i < arranged.size(); i++) place.put(arranged.get(i), i);
        Collections.sort(fresh, (a, b) -> {
            // The order someone arranged comes first; apps it has not seen yet go after it.
            Integer pa = place.get(a.component.flattenToShortString()), pb = place.get(b.component.flattenToShortString());
            if (pa != null || pb != null) {
                if (pa == null) return 1;
                if (pb == null) return -1;
                return Integer.compare(pa, pb);
            }
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
        return true;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        if (inner <= 0) inner = Math.max(0, ctx.getResources().getDisplayMetrics().widthPixels - Math.round(110 * density));
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        // The horizontal carousel keeps the same four-slot spacing as the old grid. A match-parent
        // item would otherwise occupy the whole screen and show only one app at a time.
        ViewGroup.LayoutParams itemLp = v.getLayoutParams();
        itemLp.width = Math.max(w, inner / Math.max(1, dockSize));
        v.setLayoutParams(itemLp);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, reducedMotion);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.name);
        h.name.setVisibility(View.VISIBLE);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setText(e.label);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
        }
        updateNamePlacement(h, position, focused);
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && listener.onTileHeld(pos, v)) return true;
            openAppInfo(e);
            return true;
        });
    }

    /** Show each app name at the top of its tile only while focused; hide the lower name. */
    private static void updateNamePlacement(Holder h, int position, boolean focused) {
        h.name.setVisibility(View.GONE);
        h.label.setVisibility(focused ? View.VISIBLE : View.GONE);
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) h.label.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.height = Math.round(30 * h.card.getResources().getDisplayMetrics().density);
        h.label.setLayoutParams(lp);
        h.label.setGravity(Gravity.CENTER);
        int density = Math.round(h.card.getResources().getDisplayMetrics().density);
        h.label.setPadding(10 * density, 0, 10 * density, 0);
        h.label.setBackgroundColor(0xAA000000);
    }

    /** Android's page for the app at {@code position}: storage, permissions, uninstall. */
    void openAppInfo(int position) {
        if (position >= 0 && position < items.size()) openAppInfo(items.get(position));
    }

    private void openAppInfo(Entry e) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Whether the tile at {@code position} can be moved. */
    boolean canMove(int position) {
        return position >= 0 && position < items.size();
    }

    /** The last position a moved tile can take. */
    int lastMovable() {
        return items.size() - 1;
    }

    boolean canHide(int position) {
        return items.size() > 1 && position >= 0 && position < items.size()
                && !hidden.contains(items.get(position).pkg);
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    boolean hidePackage(String pkg) {
        if (pkg == null || pkg.equals(ctx.getPackageName()) || configuredHidden.contains(pkg)
                || userHidden.contains(pkg) || items.size() <= 1) return false;
        boolean shown = false;
        for (Entry item : items) if (item.pkg.equals(pkg)) { shown = true; break; }
        if (!shown) return false;
        userHidden.add(pkg);
        hidden.add(pkg);
        saveUserHidden();
        builtFrom = null;
        return true;
    }

    boolean showPackage(String pkg) {
        if (!userHidden.remove(pkg)) return false;
        hidden.clear();
        hidden.addAll(configuredHidden);
        hidden.addAll(userHidden);
        saveUserHidden();
        builtFrom = null;
        return true;
    }

    List<String> userHiddenPackages() {
        List<String> out = new ArrayList<>(userHidden);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    Set<String> hiddenPackages() {
        return new HashSet<>(hidden);
    }

    String labelForPackage(String pkg) {
        try {
            return String.valueOf(ctx.getPackageManager().getApplicationLabel(
                    ctx.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void saveUserHidden() {
        ctx.getSharedPreferences(VISIBILITY_PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(HIDDEN_KEY, new HashSet<>(userHidden)).apply();
    }

    /**
     * Moves the tile at {@code from} to {@code to}, the tiles between shifting to make room, and keeps
     * the new order. Returns false when it cannot go there.
     */
    boolean move(int from, int to) {
        if (from == to || !canMove(from) || to < 0 || to > lastMovable()) return false;
        Entry e = items.remove(from);
        items.add(to, e);
        notifyItemMoved(from, to);
        arranged.clear();
        for (Entry x : items) arranged.add(x.component.flattenToShortString());
        ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).edit()
                .putString(ORDER_KEY, android.text.TextUtils.join("\n", arranged)).apply();
        return true;
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean reducedMotion;

        Holder(View v, TileListener listener, boolean reducedMotion) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.reducedMotion = reducedMotion;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().cancel();
                name.animate().cancel();
                int pos = getBindingAdapterPosition();
                updateNamePlacement(this, pos, focused);
                if (reducedMotion) {
                    // No overshoot: the extra frames redraw the banner and the dock band on a weak GPU.
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(ZOOM_REDUCED)
                            .withLayer()
                            .start();
                    name.setAlpha(0f);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 260 : 180)
                            .setInterpolator(focused ? ZOOM_IN : ZOOM_OUT)
                            .withLayer()
                            .start();
                    name.setAlpha(0f);
                }
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
