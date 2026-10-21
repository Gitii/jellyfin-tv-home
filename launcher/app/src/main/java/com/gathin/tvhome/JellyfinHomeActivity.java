package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Jellyfin content in front, other apps underneath. The original home remains available as All apps. */
public final class JellyfinHomeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService listings = Executors.newSingleThreadExecutor();
    private JellyfinImages images;
    private ImageView background;
    private TextView title, description, status, permissionButton;
    private LinearLayout tabs, apps;
    private RecyclerView shelf;
    private LinearLayout body;
    private List<JellyfinChannels.Row> rows = new ArrayList<>();
    private boolean active;
    private int generation, artGeneration, focusGeneration, selectedRow;
    private boolean loading;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildHome();
    }

    private void buildHome() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(background, new android.widget.FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x99000000, 0xFA080B13}));
        root.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, -1));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(36), dp(18), dp(36), dp(12));
        root.addView(body, new android.widget.FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = horizontal();
        TextView brand = text("JELLYFIN", 15, 0xFFA28BFA);
        brand.setLetterSpacing(.18f);
        bar.addView(brand, new LinearLayout.LayoutParams(0, dp(36), 1));
        permissionButton = button(getString(R.string.jellyfin_allow_listings), () ->
                requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, 71));
        bar.addView(permissionButton);
        bar.addView(button(getString(R.string.jellyfin_refresh), this::refresh));
        bar.addView(button(getString(R.string.settings), () -> startActivity(new Intent(Settings.ACTION_SETTINGS))));
        body.addView(bar);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        title = text("Jellyfin", 34, Color.WHITE);
        title.setMaxLines(2);
        description = text(getString(R.string.jellyfin_tagline), 14, 0xFFD3D6E0);
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(title, new LinearLayout.LayoutParams(-1, -2));
        hero.addView(description, new LinearLayout.LayoutParams(-1, -2));
        body.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1));

        tabs = horizontal();
        HorizontalScrollView tabScroll = horizontalScroll(tabs);
        body.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(38)));
        status = text(getString(R.string.jellyfin_loading), 14, 0xFFD3D6E0);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setMaxLines(4);
        body.addView(status, new LinearLayout.LayoutParams(-1, dp(132)));
        shelf = new RecyclerView(this);
        shelf.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        shelf.setClipToPadding(false);
        shelf.setPadding(dp(5), dp(5), dp(5), dp(5));
        shelf.setItemAnimator(null);
        shelf.setVisibility(View.GONE);
        body.addView(shelf, new LinearLayout.LayoutParams(-1, dp(132)));
        TextView appLabel = text(getString(R.string.jellyfin_apps), 11, 0xFF939AAC);
        appLabel.setPadding(0, dp(6), 0, 0);
        body.addView(appLabel, new LinearLayout.LayoutParams(-1, dp(24)));
        apps = horizontal();
        body.addView(horizontalScroll(apps), new LinearLayout.LayoutParams(-1, dp(46)));
        setContentView(root);
        loadApps();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        images = new JellyfinImages(this);
        refresh();
    }

    @Override protected void onStop() {
        active = false;
        generation++;
        artGeneration++;
        focusGeneration++;
        loading = false;
        main.removeCallbacksAndMessages(null);
        if (images != null) images.close();
        background.setImageDrawable(null);
        shelf.setAdapter(null);
        super.onStop();
    }

    @Override protected void onDestroy() {
        listings.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (active) refresh();
    }

    private void refresh() {
        if (!active || loading) return;
        boolean permitted = checkSelfPermission("android.permission.READ_TV_LISTINGS")
                == PackageManager.PERMISSION_GRANTED;
        permissionButton.setVisibility(permitted ? View.GONE : View.VISIBLE);
        if (!permitted) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.jellyfin_permission_needed);
            shelf.setVisibility(View.GONE);
            permissionButton.requestFocus();
            return;
        }
        loading = true;
        int request = ++generation;
        listings.execute(() -> {
            JellyfinChannels.Result result = JellyfinChannels.load(getApplicationContext());
            main.post(() -> {
                if (!active || request != generation) return;
                loading = false;
                rows = result.rows;
                tabs.removeAllViews();
                for (int i = 0; i < rows.size(); i++) {
                    final int index = i;
                    tabs.addView(button(rows.get(i).title, () -> selectRow(index, true)));
                }
                boolean empty = rows.isEmpty();
                status.setVisibility(empty ? View.VISIBLE : View.GONE);
                shelf.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (empty) {
                    status.setText(result.unavailable ? R.string.jellyfin_unavailable : R.string.jellyfin_empty);
                    if (apps.getChildCount() > 0) apps.getChildAt(0).requestFocus();
                } else {
                    selectRow(Math.min(selectedRow, rows.size() - 1), true);
                }
                android.util.Log.i("JellyfinHome", "Published Jellyfin rows: " + rows.size()
                        + "; restricted/unavailable: " + result.unavailable);
            });
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 71) refresh();
    }

    private void selectRow(int index, boolean focus) {
        selectedRow = index;
        shelf.setAdapter(new Programs(rows.get(index).items));
        shelf.scrollToPosition(0);
        showItem(rows.get(index).items.get(0));
        for (int i = 0; i < tabs.getChildCount(); i++) tabs.getChildAt(i).setSelected(i == index);
        if (focus) {
            int request = ++focusGeneration;
            // post() may run before RecyclerView binds its first child, especially after permission UI.
            shelf.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (!active || request != focusGeneration) {
                        shelf.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        return;
                    }
                    RecyclerView.ViewHolder first = shelf.findViewHolderForAdapterPosition(0);
                    if (first != null) {
                        shelf.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        first.itemView.requestFocus();
                    }
                }
            });
            shelf.requestLayout();
        }
    }

    private void showItem(JellyfinChannels.Item item) {
        title.setText(item.title);
        description.setText(item.description);
        int request = ++artGeneration;
        boolean[] backdropShown = {false};
        // Show published art immediately; an optional backdrop must not hold up the visible background.
        loadPublishedArtwork(JellyfinArtwork.published(item.thumbnail, item.poster), 0, request, backdropShown);
        String backdrop = JellyfinArtwork.backdrop(item.poster);
        images.load(backdrop, 1280, bitmap -> {
            if (!active || request != artGeneration) return;
            android.util.Log.d("JellyfinHome", "Backdrop decoded: " + (bitmap == null ? "none"
                    : bitmap.getWidth() + "x" + bitmap.getHeight()));
            // Jellyfin returns a square placeholder when a backdrop is missing. Don't enlarge that.
            if (bitmap != null && bitmap.getWidth() > bitmap.getHeight() * 1.3f) {
                backdropShown[0] = true;
                background.setImageBitmap(bitmap);
            }
        });
    }

    private void loadPublishedArtwork(List<String> candidates, int index, int request, boolean[] backdropShown) {
        if (!active || request != artGeneration || backdropShown[0]) return;
        if (index >= candidates.size()) {
            background.setImageDrawable(null);
            return;
        }
        images.load(candidates.get(index), 1280, bitmap -> {
            if (!active || request != artGeneration || backdropShown[0]) return;
            if (bitmap == null || isPlaceholder(bitmap)) {
                loadPublishedArtwork(candidates, index + 1, request, backdropShown);
                return;
            }
            android.util.Log.d("JellyfinHome", "Published artwork decoded: "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            if (bitmap.getWidth() < bitmap.getHeight()) {
                bitmap = HeroArt.from(new BitmapDrawable(getResources(), bitmap));
            }
            background.setImageBitmap(bitmap);
        });
    }

    private boolean isPlaceholder(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (w != h) return false;
        return JellyfinArtwork.isPlaceholder(w, h, new int[]{
                bitmap.getPixel(0, 0), bitmap.getPixel(w / 2, 0), bitmap.getPixel(w - 1, 0),
                bitmap.getPixel(0, h / 2), bitmap.getPixel(w - 1, h / 2),
                bitmap.getPixel(0, h - 1), bitmap.getPixel(w / 2, h - 1), bitmap.getPixel(w - 1, h - 1)});
    }

    private void openItem(JellyfinChannels.Item item) {
        try {
            Intent intent = Intent.parseUri(item.intent, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() != null
                    && !JellyfinChannels.PACKAGE.equals(intent.getComponent().getPackageName())) {
                openJellyfin();
                return;
            }
            intent.setSelector(null);
            intent.setPackage(JellyfinChannels.PACKAGE);
            intent.setClipData(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception ignored) {
            openJellyfin();
        }
    }

    private void openJellyfin() { openApp(JellyfinChannels.PACKAGE); }

    private void openApp(String pkg) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(pkg);
        try {
            if (intent == null) throw new IllegalStateException();
            startActivity(intent);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, R.string.jellyfin_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadApps() {
        apps.removeAllViews();
        apps.addView(button("Jellyfin", this::openJellyfin));
        apps.addView(button(getString(R.string.app_library), () -> startActivity(new Intent(this, MainActivity.class))));
        List<ResolveInfo> installed = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        installed.sort(Comparator.comparing(a -> String.valueOf(a.loadLabel(getPackageManager()))));
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(JellyfinChannels.PACKAGE);
        seen.add(getPackageName());
        for (ResolveInfo app : installed) {
            if (app.activityInfo == null || !seen.add(app.activityInfo.packageName)) continue;
            String pkg = app.activityInfo.packageName;
            apps.addView(button(String.valueOf(app.loadLabel(getPackageManager())), () -> openApp(pkg)));
            if (apps.getChildCount() >= 24) break;
        }
    }

    private final class Programs extends RecyclerView.Adapter<ProgramHolder> {
        private final List<JellyfinChannels.Item> items;
        Programs(List<JellyfinChannels.Item> items) { this.items = items; }

        @Override public ProgramHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(JellyfinHomeActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(4), dp(4), dp(4), dp(4));
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackgroundResource(R.drawable.jellyfin_focus);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(172), dp(120));
            lp.setMarginEnd(dp(12));
            card.setLayoutParams(lp);
            ImageView image = new ImageView(JellyfinHomeActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF171C2B);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            TextView label = text("", 12, Color.WHITE);
            label.setSingleLine();
            label.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
            card.addView(label, new LinearLayout.LayoutParams(-1, dp(22)));
            return new ProgramHolder(card, image, label);
        }

        @Override public void onBindViewHolder(ProgramHolder holder, int position) {
            JellyfinChannels.Item item = items.get(position);
            holder.label.setText(item.title);
            holder.itemView.setContentDescription(item.title);
            holder.image.setImageDrawable(null);
            holder.image.setTag(item);
            images.load(item.poster, 360, bitmap -> {
                if (holder.image.getTag() == item) holder.image.setImageBitmap(bitmap);
            });
            holder.itemView.setOnClickListener(v -> openItem(item));
            holder.itemView.setOnFocusChangeListener((v, focused) -> { if (focused) showItem(item); });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final class ProgramHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;
        ProgramHolder(View view, ImageView image, TextView label) {
            super(view);
            this.image = image;
            this.label = label;
        }
    }

    private TextView button(String label, Runnable action) {
        TextView view = text(label, 13, Color.WHITE);
        view.setPadding(dp(14), dp(6), dp(14), dp(6));
        view.setSingleLine();
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setBackgroundResource(R.drawable.jellyfin_focus);
        view.setOnClickListener(v -> action.run());
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused && apps != null && v.getParent() == apps && active) {
                artGeneration++;
                title.setText(v == apps.getChildAt(0) ? "Jellyfin" : label);
                description.setText(R.string.jellyfin_tagline);
            }
        });
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private HorizontalScrollView horizontalScroll(LinearLayout content) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(content);
        return scroll;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
