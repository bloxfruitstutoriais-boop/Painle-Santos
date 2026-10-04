package painel.sensi.santos;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;

final class Ui {
    static final int INK = Color.rgb(8, 4, 15);
    static final int SURFACE = Color.rgb(25, 13, 42);
    static final int SURFACE_ALT = Color.rgb(58, 25, 82);
    static final int PURPLE = Color.rgb(151, 45, 242);
    static final int BRIGHT = Color.rgb(232, 129, 255);
    static final int WHITE = Color.rgb(255, 250, 255);
    static final int MUTED = Color.rgb(242, 222, 250);
    static final int SUCCESS = Color.rgb(132, 255, 204);

    private Ui() {}
    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    static int sp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().scaledDensity); }
    static GradientDrawable rounded(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radiusDp)); return d;
    }
    static GradientDrawable outline(int fill, int stroke, int radiusDp, Context c) {
        GradientDrawable d = rounded(fill, radiusDp, c); d.setStroke(dp(c, 1), stroke); return d;
    }
    static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextColor(color); t.setTextSize(size);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false); return t;
    }
    static TextView button(Context c, String value) {
        TextView t = text(c, value, 16, INK, true); t.setGravity(android.view.Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFC98BFF, 0xFF8F42C9});
        bg.setCornerRadius(dp(c, 18)); bg.setStroke(dp(c, 1), 0xFFE5C8FF);
        t.setBackground(bg); t.setElevation(dp(c, 5));
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0); t.setClickable(true); t.setFocusable(true);
        return t;
    }
    static void addBackdrop(Context c, FrameLayout root) {
        ImageView image = new ImageView(c); image.setImageResource(R.drawable.wallpaper);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setAlpha(0.46f);
        if (Build.VERSION.SDK_INT >= 31) image.setRenderEffect(RenderEffect.createBlurEffect(dp(c, 12), dp(c, 12), Shader.TileMode.CLAMP));
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View tint = new View(c); tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE6080710, 0x990D0820, 0xF2080710}));
        root.addView(tint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    static void margin(View v, int l, int t, int r, int b, Context c) {
        if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            p.setMargins(dp(c,l), dp(c,t), dp(c,r), dp(c,b)); v.setLayoutParams(p);
        }
    }
    static void reveal(View v, long delay) {
        v.setAlpha(0f); v.setTranslationY(dp(v.getContext(), 12));
        v.postDelayed(() -> v.animate().alpha(1f).translationY(0f).setDuration(420)
                .setInterpolator(new DecelerateInterpolator()).start(), delay);
    }
    static void animatePress(View v) {
        v.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(.965f).scaleY(.965f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
            }
            return false;
        });
    }
    static void pulse(View v, long delay) {
        v.postDelayed(() -> {
            if (!v.isAttachedToWindow()) return;
            v.animate().alpha(.35f).setDuration(550).withEndAction(() ->
                    v.animate().alpha(1f).setDuration(550).withEndAction(() -> {
                        if (v.isAttachedToWindow()) pulse(v, 80);
                    }).start()).start();
        }, delay);
    }
}
