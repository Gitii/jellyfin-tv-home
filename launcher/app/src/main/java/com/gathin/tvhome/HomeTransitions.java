package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Motion transplanted from the original launcher; content loading remains independent of animation. */
final class HomeTransitions {
    private final FrameLayout root;
    private final GlassView glass;
    private final View hero, ambient;
    private final TextView title;
    private final ImageView background;
    private final float density;
    private final SpringAnimation springX, springY;
    private final Rect rect = new Rect();
    private final GlowRaster raster = new GlowRaster(480, 270);
    private final Bitmap glow = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888);
    private final BitmapDrawable glowDrawable;
    private boolean animations, reduced, advanced, shown, heroShown, glowReady;
    private float offsetX, offsetY, targetX, targetY;
    private int ambientColor, titleToken, artworkToken, glowToken;
    private Bitmap artwork, requestedArtwork;
    private String requestedTitle;
    private ValueAnimator colourAnimation;
    private View card;

    HomeTransitions(FrameLayout root, GlassView glass, View hero, TextView title, View ambient,
            ImageView background, Config config) {
        this.root = root; this.glass = glass; this.hero = hero; this.title = title;
        this.ambient = ambient; this.background = background;
        density = root.getResources().getDisplayMetrics().density;
        glowDrawable = new BitmapDrawable(root.getResources(), glow);
        glowDrawable.setGravity(Gravity.FILL);
        glowDrawable.setFilterBitmap(true);
        ambient.setBackground(glowDrawable);
        ambient.forceHasOverlappingRendering(false);
        hero.forceHasOverlappingRendering(false);
        background.forceHasOverlappingRendering(false);
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentX") {
            @Override public float getValue(View view) { return view.getTranslationX() - offsetX; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value + offsetX); }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override public float getValue(View view) { return view.getTranslationY() - offsetY; }
            @Override public void setValue(View view, float value) { view.setTranslationY(value + offsetY); }
        });
        configure(config);
    }

    void configure(Config config) {
        animations = config.animations;
        reduced = config.reducedMotion;
        advanced = animations && config.advancedEffects;
        for (SpringAnimation spring : new SpringAnimation[]{springX, springY}) {
            SpringForce force = spring.getSpring();
            if (force == null) force = new SpringForce();
            spring.setSpring(force.setStiffness(reduced ? 1400f : advanced ? 320f : 260f)
                    .setDampingRatio(reduced ? .95f : advanced ? .76f : .58f));
            spring.setMinimumVisibleChange(reduced ? 1.5f : 1f);
        }
    }

    void focus(View card, boolean featured, String label, int colour) {
        this.card = card;
        moveGlass(true);
        if (featured) {
            // A cached previous selection may still have a delayed fade-out swap queued.
            artworkToken++;
            requestedArtwork = null;
            background.animate().cancel();
            if (artwork != null) {
                if (animations) background.animate().alpha(.27f).setDuration(reduced ? 160 : 420).start();
                else background.setAlpha(.27f);
            }
            showTitle(label);
        }
        else {
            titleToken++;
            hero.animate().cancel(); hero.setAlpha(0f); hero.setTranslationX(0f);
            heroShown = false;
            clearArtwork();
        }
        glow(colour);
    }

    /** Scroll moves the whole spring in content space instead of restarting or snapping it. */
    void follow(View focused) {
        if (focused == null || focused != card || !focused.isFocused()) return;
        if (!shown) { moveGlass(false); return; }
        if (!geometry()) return;
        float x = rect.exactCenterX() - glass.getLayoutParams().width / 2f;
        float y = rect.exactCenterY() - glass.getLayoutParams().height / 2f - (advanced ? 4 * density : 0);
        float dx = x - targetX, dy = y - targetY;
        offsetX += dx; offsetY += dy;
        glass.setTranslationX(glass.getTranslationX() + dx);
        glass.setTranslationY(glass.getTranslationY() + dy);
        targetX = x; targetY = y;
    }

    private boolean geometry() {
        if (card == null || card.getWidth() == 0) return false;
        rect.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, rect);
        float scale = !animations ? 1f : advanced ? 1.12f : 1.10f;
        int w = Math.round(card.getWidth() * scale + 12 * density);
        int h = Math.round(card.getHeight() * scale + 12 * density);
        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h; glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        return true;
    }

    private void moveGlass(boolean animate) {
        if (!geometry()) return;
        targetX = rect.exactCenterX() - glass.getLayoutParams().width / 2f;
        targetY = rect.exactCenterY() - glass.getLayoutParams().height / 2f - (advanced ? 4 * density : 0);
        if (!shown || !animations) {
            springX.cancel(); springY.cancel();
            offsetX = 0; offsetY = 0;
            glass.setTranslationX(targetX); glass.setTranslationY(targetY);
            glass.animate().cancel();
            if (animations) glass.animate().alpha(1f).setDuration(reduced ? 100 : 180).start();
            else glass.setAlpha(1f);
            shown = true;
        } else if (animate) {
            springX.animateToFinalPosition(targetX - offsetX);
            springY.animateToFinalPosition(targetY - offsetY);
        }
    }

    void hideGlass() {
        card = null; shown = false;
        springX.cancel(); springY.cancel(); glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    private void showTitle(String label) {
        if (heroShown && label.equals(requestedTitle)) return;
        requestedTitle = label;
        int request = ++titleToken;
        hero.animate().cancel();
        if (!animations || reduced) {
            title.setText(label); hero.setTranslationX(0f); hero.setAlpha(1f);
        } else if (heroShown && label.contentEquals(title.getText())) {
            hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
        } else if (!heroShown) {
            title.setText(label); hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
        } else {
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                if (request != titleToken) return;
                title.setText(label); hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        }
        heroShown = true;
    }

    void glow(int colour) {
        int request = ++glowToken;
        if (colourAnimation != null) { colourAnimation.cancel(); colourAnimation = null; }
        ambient.animate().cancel();
        if (!animations || reduced) {
            tint(colour); ambient.setAlpha(1f);
        } else if (!glowReady) {
            tint(colour); ambient.setAlpha(0f); ambient.animate().alpha(1f).setDuration(600).start();
        } else if (colour == ambientColor) {
            ambient.animate().alpha(1f).setDuration(300).start();
        } else if (advanced) {
            ambient.animate().alpha(1f).setDuration(180).start();
            colourAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, colour);
            colourAnimation.setDuration(360);
            colourAnimation.addUpdateListener(value -> tint((int) value.getAnimatedValue()));
            colourAnimation.start();
        } else {
            ambient.animate().alpha(.35f).setDuration(ambient.getAlpha() > .35f ? 200 : 0).withEndAction(() -> {
                if (request != glowToken) return;
                tint(colour); ambient.animate().alpha(1f).setDuration(500).start();
            }).start();
        }
    }

    private void tint(int colour) {
        if (glowReady && colour == ambientColor) return;
        glowReady = true;
        ambientColor = colour;
        glow.setPixels(raster.tint(colour), 0, 480, 0, 0, 480, 270);
        glowDrawable.invalidateSelf();
    }

    void artwork(Bitmap bitmap) {
        if (requestedArtwork == bitmap) return;
        requestedArtwork = bitmap;
        int request = ++artworkToken;
        background.animate().cancel();
        if (artwork == bitmap) {
            if (animations) background.animate().alpha(.27f).setDuration(reduced ? 160 : 420).start();
            else background.setAlpha(.27f);
        } else if (!animations || reduced || background.getAlpha() == 0f) {
            artwork = bitmap; background.setImageBitmap(bitmap);
            if (!animations) background.setAlpha(.27f);
            else background.animate().alpha(.27f).setDuration(reduced ? 160 : 600).start();
        } else {
            background.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (request != artworkToken) return;
                artwork = bitmap; background.setImageBitmap(bitmap);
                background.animate().alpha(.27f).setDuration(420).start();
            }).start();
        }
    }

    void clearArtwork() {
        artworkToken++; artwork = null; requestedArtwork = null;
        background.animate().cancel(); background.setAlpha(0f); background.setImageDrawable(null);
    }

    void stop() {
        titleToken++; artworkToken++; glowToken++; heroShown = false; shown = false; card = null;
        requestedTitle = null; requestedArtwork = null;
        springX.cancel(); springY.cancel(); glass.animate().cancel(); glass.setAlpha(0f);
        hero.animate().cancel(); ambient.animate().cancel(); background.animate().cancel();
        if (colourAnimation != null) { colourAnimation.cancel(); colourAnimation = null; }
    }
}
