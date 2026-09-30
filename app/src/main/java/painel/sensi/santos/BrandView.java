package painel.sensi.santos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

public final class BrandView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    public BrandView(Context context) { super(context); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); float cx = getWidth() / 2f; float cy = getHeight() * .37f;
        float unit = Math.min(getWidth(), getHeight()) / 260f;
        c.save(); c.scale(unit, unit, cx, cy);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5f); p.setColor(0xFFF8F2FF); p.setShadowLayer(18, 0, 0, 0xC66D1BFF);
        Path frame = new Path(); frame.moveTo(cx-122,cy-44); frame.lineTo(cx-85,cy-88); frame.lineTo(cx-37,cy-67); frame.lineTo(cx,cy-106); frame.lineTo(cx+42,cy-70); frame.lineTo(cx+91,cy-92); frame.lineTo(cx+124,cy-42); frame.lineTo(cx+104,cy+12); frame.lineTo(cx+132,cy+47); frame.lineTo(cx+81,cy+67); frame.lineTo(cx+42,cy+106); frame.lineTo(cx,cy+80); frame.lineTo(cx-42,cy+108); frame.lineTo(cx-81,cy+68); frame.lineTo(cx-131,cy+47); frame.lineTo(cx-103,cy+10); frame.close(); c.drawPath(frame,p); p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF7E20B5);
        Path shards = new Path(); shards.moveTo(cx-74,cy-60); shards.lineTo(cx-38,cy-96); shards.lineTo(cx-29,cy-43); shards.lineTo(cx-62,cy-22); shards.lineTo(cx-88,cy-42); shards.close(); c.drawPath(shards,p);
        shards.reset(); shards.moveTo(cx-14,cy-92); shards.lineTo(cx+12,cy-112); shards.lineTo(cx+18,cy-47); shards.lineTo(cx-6,cy-29); shards.close(); c.drawPath(shards,p);
        shards.reset(); shards.moveTo(cx+37,cy-76); shards.lineTo(cx+76,cy-93); shards.lineTo(cx+52,cy-36); shards.lineTo(cx+18,cy-25); shards.close(); c.drawPath(shards,p);
        shards.reset(); shards.moveTo(cx-89,cy+55); shards.lineTo(cx-46,cy+34); shards.lineTo(cx-32,cy+98); shards.lineTo(cx-68,cy+70); shards.close(); c.drawPath(shards,p);
        shards.reset(); shards.moveTo(cx+4,cy+33); shards.lineTo(cx+42,cy+44); shards.lineTo(cx+67,cy+91); shards.lineTo(cx+18,cy+70); shards.close(); c.drawPath(shards,p);
        p.setColor(0xFFF8F2FF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4f); Path crest=new Path(); crest.moveTo(cx-35,cy-30); crest.lineTo(cx,cy-50); crest.lineTo(cx+35,cy-30); crest.lineTo(cx+28,cy+14); crest.lineTo(cx,cy+32); crest.lineTo(cx-28,cy+14); crest.close(); c.drawPath(crest,p);
        text.setColor(Ui.WHITE); text.setTextAlign(Paint.Align.CENTER); text.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD)); text.setTextSize(51f); text.setShadowLayer(7,0,0,0xAA8A35FF); c.drawText("SANTOS",cx,cy+58,text); text.setTextSize(25f); c.drawText("TEAM",cx+56,cy+84,text); text.clearShadowLayer(); c.restore();
    }
}
