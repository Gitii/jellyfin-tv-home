package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Original launcher chrome and tiles: featured Jellyfin content, libraries, then the configured apps. */
public final class JellyfinHomeActivity extends Activity {
    private static final int FEATURED = 0, LIBRARIES = 1, APPS = 2;
    private static final int ROW_DP = 150;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService listings = Executors.newSingleThreadExecutor();
    private final List<Section> sections = new ArrayList<>();
    private Config config;
    private AppAdapter appSource;
    private JellyfinImages images;
    private FrameLayout root;
    private RecyclerView rows;
    private LinearLayoutManager rowLayout;
    private ImageView background;
    private View hero, ambient;
    private TextView title, status;
    private GlassView glass;
    private HomeTransitions transitions;
    private PopupWindow popup;
    private float density;
    private int artworkWidth;
    private boolean active, loading, resetFocus = true;
    private int generation, artGeneration, focusedRow, focusedColumn;
    private int pendingRow = -1, pendingColumn;
    private String focusedKey;
    private View focusedCard;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
        fulfillFocus();
        fitGlass();
    };

    private static final class Tile {
        final JellyfinChannels.Item item;
        final AppAdapter.Entry app;
        final String key, label;
        Tile(JellyfinChannels.Item item) { this.item = item; app = null; key = item.key; label = item.title; }
        Tile(AppAdapter.Entry app) { this.app = app; item = null; key = app.component.flattenToString(); label = app.label; }
        Drawable artwork() { return app == null ? null : app.banner != null ? app.banner : app.icon; }
    }

    private static final class Section {
        final int kind;
        final String label;
        final List<Tile> tiles = new ArrayList<>();
        Section(int kind, String label) { this.kind = kind; this.label = label; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = Config.load(this);
        density = getResources().getDisplayMetrics().density;
        // A 4K source is sampled for the actual app surface, not blindly kept as a 32 MiB texture.
        artworkWidth = Math.max(1280, Math.min(1920, getResources().getDisplayMetrics().widthPixels));
        images = new JellyfinImages(this);
        appSource = new AppAdapter(this, null, 4, config);
        appSource.setSavedOrder(Order.ranks(Order.load(Order.file(this)), config.dockApps));
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        root.setBackgroundColor(Color.BLACK);
        background = findViewById(R.id.heroArt);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setVisibility(View.VISIBLE);
        ambient = findViewById(R.id.ambient);
        glass = findViewById(R.id.glass);
        hero = findViewById(R.id.hero);
        title = findViewById(R.id.heroName);
        FrameLayout.LayoutParams heroLp = (FrameLayout.LayoutParams) hero.getLayoutParams();
        heroLp.topMargin = dp(133);
        hero.setLayoutParams(heroLp);
        title.setMaxWidth(dp(800));
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        findViewById(R.id.heroMeta).setVisibility(View.GONE);
        findViewById(R.id.heroDots).setVisibility(View.GONE);
        // The artwork is already dimmed. A second full-screen gradient adds visible dark colour steps.
        findViewById(R.id.heroScrim).setVisibility(View.GONE);
        transitions = new HomeTransitions(root, glass, hero, title, ambient, background, config);

        RecyclerView originalGrid = findViewById(R.id.grid);
        FrameLayout wrapper = (FrameLayout) originalGrid.getParent();
        wrapper.removeView(originalGrid);
        rows = new RecyclerView(this);
        rows.setClipToPadding(false);
        rows.setClipChildren(false);
        rows.setItemAnimator(null);
        rowLayout = new LinearLayoutManager(this) {
            @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child,
                    Rect rect, boolean immediate, boolean focusedChildVisible) {
                return false; // The explicit D-pad navigation parks the preceding row above the focused one.
            }
        };
        rows.setLayoutManager(rowLayout);
        rows.setAdapter(new Sections());
        wrapper.addView(rows, new FrameLayout.LayoutParams(-1, -1));
        rows.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { fulfillFocus(); fitGlass(); }
            @Override public void onScrollStateChanged(RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) fulfillFocus();
            }
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        wrapper.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int[] location = new int[2], origin = new int[2];
            rows.getLocationInWindow(location);
            root.getLocationInWindow(origin);
            int padding = Math.max(dp(12), dp(198) - location[1] + origin[1]);
            if (rows.getPaddingTop() != padding) rows.setPadding(0, padding, 0, dp(20));
        });
        status = new TextView(this);
        status.setTextColor(0xFFCCCCCC);
        status.setTextSize(14);
        status.setMaxLines(2);
        status.setTypeface(getResources().getFont(R.font.outfit));
        status.setFocusable(true);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setBackgroundResource(R.drawable.pill_bg);
        status.setOnClickListener(v -> {
            if (!hasListingsPermission()) requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, 71);
            else openJellyfin();
        });
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(dp(800), -2);
        statusLp.leftMargin = dp(56);
        statusLp.topMargin = dp(140);
        root.addView(status, statusLp);
        setUpHeader();
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldView, newView) -> {
            if (newView == null || newView.getId() != R.id.card) {
                focusedCard = null;
                transitions.hideGlass();
            }
        });
    }

    private void setUpHeader() {
        View inputs = findViewById(R.id.btnInputs), network = findViewById(R.id.btnWifi);
        View settings = findViewById(R.id.btnSettings);
        inputs.setOnClickListener(v -> showInputs());
        network.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        network.setOnLongClickListener(v -> { open(new Intent(Settings.ACTION_WIFI_SETTINGS)); return true; });
        settings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        settings.setOnLongClickListener(v -> { showMenu(); return true; });
        findViewById(R.id.btnReorder).setVisibility(View.GONE);
        for (View button : new View[]{inputs, network, settings}) button.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focus(0, focusedColumn); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && v == inputs) return true;
            return key == KeyEvent.KEYCODE_DPAD_RIGHT && v == settings;
        });
    }

    private void updateHeader() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int greeting = hour < 12 ? R.string.good_morning : hour < 18 ? R.string.good_afternoon : R.string.good_evening;
        ((TextView) findViewById(R.id.greeting)).setText(greeting);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        TextView network = findViewById(R.id.btnWifi);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            network.setText(R.string.ethernet);
            network.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ethernet, 0, 0, 0);
        } else network.setText(R.string.wifi);
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        updateHeader();
        refresh();
    }

    @Override protected void onStop() {
        active = false;
        generation++;
        artGeneration++;
        loading = false;
        pendingRow = -1;
        focusedCard = null;
        if (popup != null) popup.dismiss();
        transitions.stop();
        super.onStop();
        // Keep the bounded image cache while another app is in front; returning Home need not fetch again.
    }

    @Override protected void onDestroy() {
        root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        listings.shutdownNow();
        images.close();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (images != null && (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_BACKGROUND)) images.trim();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetFocus = true;
        if (active) {
            focus(0, 0);
            refresh();
        }
    }

    private boolean hasListingsPermission() {
        return checkSelfPermission("android.permission.READ_TV_LISTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 71) refresh();
    }

    private void refresh() {
        if (!active || loading) return;
        loading = true;
        int request = ++generation;
        boolean permitted = hasListingsPermission();
        listings.execute(() -> {
            JellyfinChannels.Result content = permitted ? JellyfinChannels.load(getApplicationContext())
                    : new JellyfinChannels.Result(Collections.emptyList(), false);
            main.post(() -> {
                if (!active || request != generation) return;
                loading = false;
                rebuild(content);
                status.setVisibility(content.featured.isEmpty() && content.libraries.isEmpty() ? View.VISIBLE : View.GONE);
                status.setText(!permitted ? R.string.jellyfin_permission_needed
                        : content.unavailable ? R.string.jellyfin_unavailable : R.string.jellyfin_empty);
                if (!permitted) status.requestFocus();
                android.util.Log.i("JellyfinHome", "Featured: " + content.featured.size()
                        + "; libraries: " + content.libraries.size() + "; provider unavailable: " + content.unavailable);
            });
        });
    }

    private void rebuild(JellyfinChannels.Result content) {
        sections.clear();
        if (!content.featured.isEmpty()) {
            Section section = new Section(FEATURED, getString(R.string.jellyfin_featured));
            for (JellyfinChannels.Item item : content.featured) section.tiles.add(new Tile(item));
            sections.add(section);
        }
        if (!content.libraries.isEmpty()) {
            Section section = new Section(LIBRARIES, getString(R.string.jellyfin_libraries));
            for (JellyfinChannels.Item item : content.libraries) section.tiles.add(new Tile(item));
            sections.add(section);
        }
        config = Config.load(this);
        transitions.configure(config);
        appSource = new AppAdapter(this, null, 4, config);
        appSource.setSavedOrder(Order.ranks(Order.load(Order.file(this)), config.dockApps));
        appSource.reload(Collections.emptyList());
        Section appRow = null;
        for (AppAdapter.Entry app : appSource.entries()) {
            if (app.kind == AppAdapter.Entry.EMPTY) continue;
            if (appRow == null || appRow.tiles.size() == 4) {
                appRow = new Section(APPS, appRow == null ? getString(R.string.jellyfin_apps) : "");
                sections.add(appRow);
            }
            appRow.tiles.add(new Tile(app));
        }
        focusedCard = null;
        transitions.hideGlass();
        rows.getAdapter().notifyDataSetChanged();
        int row = 0, column = 0;
        if (!resetFocus && focusedKey != null) {
            for (int r = 0; r < sections.size(); r++) for (int c = 0; c < sections.get(r).tiles.size(); c++) {
                if (focusedKey.equals(sections.get(r).tiles.get(c).key)) { row = r; column = c; }
            }
        }
        resetFocus = false;
        if (hasListingsPermission()) focus(row, column);
    }

    private void focus(int row, int column) {
        if (sections.isEmpty()) return;
        pendingRow = Math.max(0, Math.min(row, sections.size() - 1));
        pendingColumn = Math.max(0, Math.min(column, sections.get(pendingRow).tiles.size() - 1));
        int anchorPosition = Math.max(0, pendingRow - 1);
        View anchor = rowLayout.findViewByPosition(anchorPosition);
        if (anchor == null) rowLayout.scrollToPositionWithOffset(anchorPosition, 0);
        else {
            int dy = anchor.getTop() - rows.getPaddingTop();
            if (Math.abs(dy) > dp(1)) {
                if (config.animations && !config.reducedMotion) rows.smoothScrollBy(0, dy);
                else rows.scrollBy(0, dy);
            }
        }
        rows.requestLayout();
    }

    private void fulfillFocus() {
        if (!active || pendingRow < 0) return;
        SectionHolder section = (SectionHolder) rows.findViewHolderForAdapterPosition(pendingRow);
        if (section == null) return;
        RecyclerView.ViewHolder holder = section.tiles.findViewHolderForAdapterPosition(pendingColumn);
        if (holder == null) {
            if (config.animations && !config.reducedMotion) {
                if (section.tiles.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) section.tiles.smoothScrollToPosition(pendingColumn);
            } else ((LinearLayoutManager) section.tiles.getLayoutManager()).scrollToPositionWithOffset(Math.max(0, pendingColumn - 3), 0);
            return;
        }
        int dx = holder.itemView.getLeft() < 0 ? holder.itemView.getLeft()
                : Math.max(0, holder.itemView.getRight() - section.tiles.getWidth());
        if (dx != 0) {
            if (section.tiles.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                if (config.animations && !config.reducedMotion) section.tiles.smoothScrollBy(dx, 0);
                else section.tiles.scrollBy(dx, 0);
            }
            return; // Keep the latest request until its tile is fully visible.
        }
        // An older smooth scroll can still carry a visible reversed target offscreen.
        if (section.tiles.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
        View card = holder.itemView.findViewById(R.id.card);
        pendingRow = -1;
        card.requestFocus();
    }

    private void focused(int row, int column, View card) {
        if (!active || row >= sections.size()) return;
        focusedRow = row;
        focusedColumn = column;
        focusedCard = card;
        Section section = sections.get(row);
        Tile tile = section.tiles.get(column);
        focusedKey = tile.key;
        int color = AppAdapter.vividColor(tile.artwork());
        if (tile.item != null) {
            ImageView banner = ((View) card.getParent()).findViewById(R.id.banner);
            if (banner.getDrawable() != null) color = AppAdapter.vividColor(banner.getDrawable());
        }
        transitions.focus(card, section.kind == FEATURED, tile.label, color);
        int request = ++artGeneration;
        if (section.kind != FEATURED) {
            return; // Libraries and apps never request backgrounds, including after late image completions.
        }
        showArtwork(tile.item, request);
        for (int neighbor : JellyfinArtwork.neighbors(column, section.tiles.size())) {
            images.prefetch(JellyfinArtwork.backdrop(section.tiles.get(neighbor).item.poster), artworkWidth);
        }
    }

    private void fitGlass() {
        transitions.follow(focusedCard);
    }

    private boolean usable(Bitmap bitmap) {
        if (bitmap == null) return false;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        return !JellyfinArtwork.isPlaceholder(w, h, new int[]{bitmap.getPixel(0, 0), bitmap.getPixel(w / 2, 0),
                bitmap.getPixel(w - 1, 0), bitmap.getPixel(0, h / 2), bitmap.getPixel(w - 1, h / 2),
                bitmap.getPixel(0, h - 1), bitmap.getPixel(w / 2, h - 1), bitmap.getPixel(w - 1, h - 1)});
    }

    private void showArtwork(JellyfinChannels.Item item, int request) {
        String source = JellyfinArtwork.backdrop(item.poster);
        Bitmap cached = images.peek(source, artworkWidth);
        if (usable(cached) && cached.getWidth() > cached.getHeight() * 1.3f) {
            transitions.artwork(cached);
            android.util.Log.d("JellyfinHome", "Backdrop cache hit");
        }
        images.load(source, artworkWidth, () -> active && request == artGeneration, bitmap -> {
            if (!active || request != artGeneration) return;
            if (usable(bitmap) && bitmap.getWidth() > bitmap.getHeight() * 1.3f) {
                transitions.artwork(bitmap);
                android.util.Log.d("JellyfinHome", "Backdrop ready: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                images.load(JellyfinArtwork.thumbnail(item.poster), artworkWidth, () -> active && request == artGeneration, fallback -> {
                    if (!active || request != artGeneration) return;
                    if (usable(fallback) && fallback.getWidth() > fallback.getHeight() * 1.3f) {
                        transitions.artwork(fallback);
                    } else transitions.clearArtwork();
                });
            }
        });
    }

    private final class Sections extends RecyclerView.Adapter<SectionHolder> {
        @Override public SectionHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout section = new LinearLayout(JellyfinHomeActivity.this);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setClipChildren(false);
            section.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(ROW_DP)));
            TextView label = new TextView(JellyfinHomeActivity.this);
            label.setTextSize(10);
            label.setTextColor(0x77FFFFFF);
            label.setTypeface(getResources().getFont(R.font.outfit_medium));
            label.setLetterSpacing(.17f);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(dp(16), 0, 0, 0);
            section.addView(label, new LinearLayout.LayoutParams(-1, dp(22)));
            RecyclerView tiles = new RecyclerView(JellyfinHomeActivity.this);
            tiles.setLayoutManager(new LinearLayoutManager(JellyfinHomeActivity.this, RecyclerView.HORIZONTAL, false) {
                @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child,
                        Rect rect, boolean immediate, boolean focusedChildVisible) { return false; }
            });
            tiles.setClipChildren(false);
            tiles.setClipToPadding(false);
            tiles.setItemAnimator(null);
            tiles.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(RecyclerView view, int dx, int dy) { fulfillFocus(); fitGlass(); }
                @Override public void onScrollStateChanged(RecyclerView view, int state) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) fulfillFocus();
                }
            });
            section.addView(tiles, new LinearLayout.LayoutParams(-1, dp(128)));
            return new SectionHolder(section, label, tiles);
        }

        @Override public void onBindViewHolder(SectionHolder holder, int position) {
            holder.label.setText(sections.get(position).label.toUpperCase(Locale.getDefault()));
            holder.tiles.setAdapter(new Tiles(position));
        }
        @Override public int getItemCount() { return sections.size(); }
    }

    private static final class SectionHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final RecyclerView tiles;
        SectionHolder(View view, TextView label, RecyclerView tiles) { super(view); this.label = label; this.tiles = tiles; }
    }

    private final class Tiles extends RecyclerView.Adapter<AppAdapter.Holder> implements AppAdapter.TileListener {
        private final int row;
        Tiles(int row) { this.row = row; }
        @Override public AppAdapter.Holder onCreateViewHolder(ViewGroup parent, int type) {
            View view = getLayoutInflater().inflate(R.layout.item_app, parent, false);
            view.setPadding(0, 0, 0, 0);
            int innerWidth = parent.getWidth() > 0 ? parent.getWidth()
                    : getResources().getDisplayMetrics().widthPixels - dp(112);
            view.setLayoutParams(new RecyclerView.LayoutParams(innerWidth / 4, dp(128)));
            int width = AppAdapter.tileWidthPx(innerWidth, 4, density);
            int height = AppAdapter.tileHeightPx(width);
            View card = view.findViewById(R.id.card);
            card.getLayoutParams().width = width;
            card.getLayoutParams().height = height;
            FrameLayout.LayoutParams nameLp = (FrameLayout.LayoutParams) view.findViewById(R.id.name).getLayoutParams();
            nameLp.width = width;
            nameLp.topMargin = height + dp(7);
            nameLp.height = dp(20);
            view.findViewById(R.id.name).setLayoutParams(nameLp);
            return new AppAdapter.Holder(view, this, config.animations,
                    config.animations && config.advancedEffects, config.reducedMotion);
        }

        @Override public void onBindViewHolder(AppAdapter.Holder holder, int position) {
            Tile tile = sections.get(row).tiles.get(position);
            holder.name.setText(tile.label);
            holder.name.setVisibility(sections.get(row).kind == FEATURED ? View.INVISIBLE : View.VISIBLE);
            holder.name.setAlpha(0f);
            holder.card.setContentDescription(tile.label);
            holder.card.setTag(tile.key);
            holder.card.setTag(R.id.name, Boolean.TRUE);
            holder.card.setOnClickListener(v -> { if (tile.app != null) appSource.launch(tile.app); else openItem(tile.item); });
            holder.card.setScaleX(1f); holder.card.setScaleY(1f);
            holder.banner.setImageDrawable(null);
            holder.banner.setVisibility(View.VISIBLE);
            holder.icon.setVisibility(View.GONE);
            holder.label.setVisibility(View.GONE);
            if (tile.app != null) {
                if (tile.app.banner != null) holder.banner.setImageDrawable(tile.app.banner);
                else {
                    holder.banner.setVisibility(View.GONE);
                    holder.icon.setImageDrawable(tile.app.icon); holder.icon.setVisibility(View.VISIBLE);
                    holder.label.setText(tile.label); holder.label.setVisibility(View.VISIBLE);
                }
            } else loadTile(holder, tile, sections.get(row).kind == FEATURED);
        }

        @Override public void onViewDetachedFromWindow(AppAdapter.Holder holder) {
            holder.card.setTag(R.id.name, Boolean.FALSE);
        }

        @Override public void onViewAttachedToWindow(AppAdapter.Holder holder) {
            boolean detached = Boolean.FALSE.equals(holder.card.getTag(R.id.name));
            holder.card.setTag(R.id.name, Boolean.TRUE);
            int position = holder.getBindingAdapterPosition();
            if (detached && position != RecyclerView.NO_POSITION && row < sections.size()
                    && holder.banner.getDrawable() == null) {
                Tile tile = sections.get(row).tiles.get(position);
                if (tile.item != null) loadTile(holder, tile, sections.get(row).kind == FEATURED);
            }
        }

        @Override public void onViewRecycled(AppAdapter.Holder holder) {
            holder.card.setTag(null);
            holder.card.setTag(R.id.name, Boolean.FALSE);
        }

        private void loadTile(AppAdapter.Holder holder, Tile tile, boolean featured) {
            List<String> candidates = new ArrayList<>();
            if (featured) candidates.add(JellyfinArtwork.thumbnail(tile.item.poster));
            candidates.addAll(JellyfinArtwork.published(tile.item.thumbnail, tile.item.poster));
            loadTileImage(holder, tile, candidates, 0);
        }

        private void loadTileImage(AppAdapter.Holder holder, Tile tile, List<String> candidates, int index) {
            if (index >= candidates.size()) { holder.label.setText(tile.label); holder.label.setVisibility(View.VISIBLE); return; }
            images.load(candidates.get(index), dp(200),
                    () -> active && tile.key.equals(holder.card.getTag())
                            && Boolean.TRUE.equals(holder.card.getTag(R.id.name)), bitmap -> {
                if (!active || !tile.key.equals(holder.card.getTag())) return;
                if (!usable(bitmap)) { loadTileImage(holder, tile, candidates, index + 1); return; }
                holder.banner.setImageBitmap(bitmap);
                if (holder.card.isFocused()) transitions.glow(AppAdapter.vividColor(new BitmapDrawable(getResources(), bitmap)));
            });
        }

        @Override public int getItemCount() { return row < sections.size() ? sections.get(row).tiles.size() : 0; }
        @Override public void onTileFocused(int position, View card) { focused(row, position, card); }
        @Override public boolean onTileVerticalKey(int position, int key) {
            if (pendingRow == row) position = pendingColumn;
            SectionHolder holder = (SectionHolder) rows.findViewHolderForAdapterPosition(row);
            int first = holder == null ? 0 : Math.max(0,
                    ((LinearLayoutManager) holder.tiles.getLayoutManager()).findFirstVisibleItemPosition());
            int column = Math.min(3, Math.max(0, position - first));
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (row == 0) findViewById(R.id.btnInputs).requestFocus(); else focus(row - 1, column);
            } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) focus(Math.min(sections.size() - 1, row + 1), column);
            else if (key == KeyEvent.KEYCODE_DPAD_LEFT) focus(row, Math.max(0, position - 1));
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) focus(row, Math.min(getItemCount() - 1, position + 1));
            else return false;
            return true;
        }
        @Override public void onLibraryRequested() { }
        @Override public void onLibraryFocused() { }
        @Override public void onLibraryUp() { }
    }

    private void openItem(JellyfinChannels.Item item) {
        try {
            Intent intent = Intent.parseUri(item.intent, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() != null && !JellyfinChannels.PACKAGE.equals(intent.getComponent().getPackageName())) {
                openJellyfin(); return;
            }
            intent.setSelector(null); intent.setPackage(JellyfinChannels.PACKAGE); intent.setClipData(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open(intent);
        } catch (Exception ignored) { openJellyfin(); }
    }

    private void openJellyfin() {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(JellyfinChannels.PACKAGE);
        if (intent != null) open(intent);
    }

    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException ignored) { Toast.makeText(this, R.string.jellyfin_open_failed, Toast.LENGTH_SHORT).show(); }
    }

    private LinearLayout menu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout items = panel.findViewById(R.id.rows);
        popup = new PopupWindow(panel, dp(280), -2, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.showAsDropDown(anchor, 0, dp(10), Gravity.END);
        return items;
    }

    private void menuItem(LinearLayout items, String text, Runnable action) {
        View item = getLayoutInflater().inflate(R.layout.item_input, items, false);
        ((TextView) item.findViewById(R.id.title)).setText(text);
        item.findViewById(R.id.icon).setVisibility(View.GONE);
        item.findViewById(R.id.dot).setVisibility(View.GONE);
        item.findViewById(R.id.status).setVisibility(View.GONE);
        item.setOnClickListener(v -> { popup.dismiss(); action.run(); });
        items.addView(item);
        if (items.getChildCount() == 1) item.requestFocus();
    }

    private void showMenu() {
        LinearLayout items = menu(findViewById(R.id.btnSettings));
        menuItem(items, getString(R.string.jellyfin_refresh), this::refresh);
        if (!hasListingsPermission()) menuItem(items, getString(R.string.jellyfin_allow_listings), () ->
                requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, 71));
        menuItem(items, getString(R.string.jellyfin_original_home), () -> open(new Intent(this, MainActivity.class)));
    }

    private void showInputs() {
        TvInputManager manager = getSystemService(TvInputManager.class);
        if (manager == null) { Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show(); return; }
        List<TvInputInfo> inputs = new ArrayList<>(manager.getTvInputList());
        inputs.removeIf(input -> !input.isPassthroughInput() || input.isHidden(this));
        inputs.sort((a, b) -> String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
        if (inputs.isEmpty()) { Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show(); return; }
        LinearLayout items = menu(findViewById(R.id.btnInputs));
        for (TvInputInfo input : inputs) {
            if (inputs.stream().anyMatch(other -> input.getId().equals(other.getParentId()))) continue;
            CharSequence label = input.loadCustomLabel(this);
            String text = String.valueOf(label == null || label.length() == 0 ? input.loadLabel(this) : label);
            menuItem(items, text, () -> open(new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(input.getId()))));
        }
    }

    private int dp(int value) { return Math.round(value * density); }
}
