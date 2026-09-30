package painel.sensi.santos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** Roda de matiz compacta para a configuração de cor do painel. */
public final class ColorWheelView extends View {
    public interface OnColorChangedListener { void onColorChanged(int color); }

    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float hue;
    private int color = Ui.BRIGHT;
    private OnColorChangedListener listener;

    public ColorWheelView(Context context) {
        super(context);
        setFocusable(true);
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(Ui.dp(context, 2));
        markerPaint.setColor(Color.WHITE);
        setContentDescription("Roda de cor");
    }

    public void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    public void setColor(int color) {
        this.color = color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = Math.max(1f, Math.min(getWidth(), getHeight()) * .38f);
        wheelPaint.setStyle(Paint.Style.STROKE);
        wheelPaint.setStrokeWidth(Math.max(Ui.dp(getContext(), 18), radius * .18f));
        wheelPaint.setShader(new SweepGradient(centerX, centerY,
                new int[]{Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
                        Color.GREEN, Color.YELLOW, Color.RED}, null));
        bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        canvas.drawArc(bounds, 0f, 360f, false, wheelPaint);
        wheelPaint.setShader(null);
        wheelPaint.setStyle(Paint.Style.FILL);
        wheelPaint.setColor(color);
        canvas.drawCircle(centerX, centerY, radius * .60f, wheelPaint);
        double angle = Math.toRadians(hue - 90f);
        float markerRadius = radius + wheelPaint.getStrokeWidth() * .5f;
        float markerX = centerX + (float) Math.cos(angle) * markerRadius;
        float markerY = centerY + (float) Math.sin(angle) * markerRadius;
        canvas.drawCircle(markerX, markerY, Ui.dp(getContext(), 7), markerPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN
                && event.getAction() != MotionEvent.ACTION_MOVE
                && event.getAction() != MotionEvent.ACTION_UP) return true;
        float dx = event.getX() - getWidth() / 2f;
        float dy = event.getY() - getHeight() / 2f;
        if (Math.hypot(dx, dy) < Math.min(getWidth(), getHeight()) * .22f) return true;
        hue = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 90d + 360d) % 360d);
        color = Color.HSVToColor(new float[]{hue, 0.82f, 0.95f});
        invalidate();
        if (listener != null) listener.onColorChanged(color);
        performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
