package painel.sensi.santos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class FeatureIconView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int index;
    FeatureIconView(Context c, int index) { super(c); this.index=index; }
    @Override protected void onDraw(Canvas c) {
        float d=getResources().getDisplayMetrics().density, cx=getWidth()/2f, cy=getHeight()/2f;
        // CORRIGIDO: ícones seguem a cor de destaque discreta do painel.
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(3.2f*d); p.setColor(Ui.BRIGHT);
        if(index==0) drawFingerprint(c,cx,cy,d); else if(index==1) drawShield(c,cx,cy,d); else if(index==2) drawSliders(c,cx,cy,d); else drawLock(c,cx,cy,d);
    }
    private void drawFingerprint(Canvas c,float x,float y,float d){
        for(int i=0;i<4;i++){float r=(11+i*5)*d; c.drawArc(x-r,y-r*.8f,x+r,y+r*.85f,205,130,false,p);}
        c.drawArc(x-6*d,y-2*d,x+6*d,y+18*d,40,250,false,p); c.drawArc(x-2*d,y+2*d,x+8*d,y+20*d,25,210,false,p);
    }
    private void drawShield(Canvas c,float x,float y,float d){Path s=new Path();s.moveTo(x,y-23*d);s.lineTo(x+18*d,y-14*d);s.lineTo(x+16*d,y+9*d);s.quadTo(x,y+25*d,x,y+25*d);s.quadTo(x-16*d,y+9*d,x-18*d,y-14*d);s.close();p.setStyle(Paint.Style.FILL);c.drawPath(s,p);// CORRIGIDO: preenchimento secundário não usa mais roxo saturado.
        p.setColor(Ui.PURPLE);Path cut=new Path();cut.moveTo(x,y-20*d);cut.lineTo(x,y+19*d);cut.lineTo(x+16*d,y+8*d);cut.lineTo(x+17*d,y-13*d);cut.close();c.drawPath(cut,p);}
    private void drawSliders(Canvas c,float x,float y,float d){c.drawLine(x-20*d,y-15*d,x+20*d,y-15*d,p);c.drawLine(x-20*d,y,x+20*d,y,p);c.drawLine(x-20*d,y+15*d,x+20*d,y+15*d,p);p.setStyle(Paint.Style.FILL);c.drawRect(x-5*d,y-20*d,x+2*d,y-10*d,p);c.drawRect(x+8*d,y-5*d,x+15*d,y+5*d,p);c.drawRect(x-13*d,y+10*d,x-6*d,y+20*d,p);}
    private void drawLock(Canvas c,float x,float y,float d){p.setStyle(Paint.Style.STROKE);c.drawRoundRect(x-15*d,y-4*d,x+15*d,y+22*d,4*d,4*d,p);c.drawArc(x-10*d,y-24*d,x+10*d,y+6*d,180,-180,false,p);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y+9*d,3*d,p);}
}
