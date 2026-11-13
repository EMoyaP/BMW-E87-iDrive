package com.ug.e87idrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;

/** Compact right-side live incident card for the driving screen. */
final class TrafficIncidentNoticeView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card=new RectF();
    private TrafficIncidentRepository.Alert alert;
    TrafficIncidentNoticeView(Context context){super(context);setWillNotDraw(false);}
    void setAlert(TrafficIncidentRepository.Alert value){alert=value;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(alert==null)return;
        int tone=alert.incident.severity==TrafficIncidentRepository.RED
                ?Color.rgb(224,54,54):Color.rgb(244,156,42);
        float w=getWidth(),h=getHeight();
        card.set(2,2,w-2,h-2);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(3,17,29));
        canvas.drawRoundRect(card,9,9,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(tone);
        canvas.drawRoundRect(card,9,9,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(tone);canvas.drawCircle(31,h/2,18,paint);
        paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(23);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);canvas.drawText("!",31,h/2+8,paint);
        paint.setTextAlign(Paint.Align.LEFT);paint.setColor(tone);paint.setTextSize(15);canvas.drawText(alert.incident.title,60,28,paint);
        paint.setColor(Color.WHITE);paint.setTextSize(18);canvas.drawText(alert.incident.road,60,55,paint);
        paint.setTextSize(25);canvas.drawText(distance(alert.distanceMeters),60,87,paint);
        paint.setColor(Color.rgb(159,183,201));paint.setTextSize(12);
        String detail=alert.incident.source+" · EN DIRECTO"
                +(alert.incident.detail.isEmpty()?"":" · "+alert.incident.detail);
        canvas.drawText(fit(detail,Math.max(20f,w-70f),paint),60,111,paint);
    }
    private static String fit(String value,float width,Paint paint){
        if(value==null||paint.measureText(value)<=width)return value==null?"":value;
        String suffix="…";float available=Math.max(0f,width-paint.measureText(suffix));
        int count=paint.breakText(value,true,available,null);
        return value.substring(0,Math.max(0,count)).trim()+suffix;
    }
    private static String distance(double meters){return meters<1000?Math.round(meters)+" m":String.format(Locale.getDefault(),"%.1f km",meters/1000d);}
}
