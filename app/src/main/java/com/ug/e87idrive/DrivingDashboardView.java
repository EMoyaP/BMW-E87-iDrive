package com.ug.e87idrive;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.FrameLayout;
import java.util.Locale;

/** Native, resolution-independent driving cluster. No simulated vehicle values. */
final class DrivingDashboardView extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path rim = new Path(), band = new Path(), fill = new Path(), outer = new Path();
    private final PathMeasure rimMeasure;
    private final float[] point = new float[2], tangent = new float[2];
    // Landmarks traced from the approved 1661 x 922 reference, not equal-angle divisions.
    private static final int[] DIVISIONS = {0,20,40,60,100,140,180,220,260};
    private static final float[][] MARKS = {{181,658},{96,585},{48,486},{34,382},
            {71,269},{145,164},{258,99},{368,82},{526,81}};
    private static final float[][] LABELS = {{221,629},{158,568},{114,490},{101,394},
            {135,302},{200,225},{294,171},{406,153},{548,143}};
    private final float[] divisionDistances = new float[DIVISIONS.length];
    private final Path[] luminousStrips = new Path[44];
    private double renderedFillSpeed = Double.NaN;
    private final Shader bandShader = new LinearGradient(0, 60, 0, 530,
            new int[]{Color.rgb(4,22,37),Color.rgb(2,13,24),Color.rgb(5,32,51)}, null, Shader.TileMode.CLAMP);
    private final Bitmap car;
    private final Bitmap brand;
    private final Rect brandSource = new Rect(88,88,424,424);
    private final RectF brandDestination = new RectF(541,31,601,91);
    private final Path emblemClip = new Path();
    private final Shader brandLine = new LinearGradient(465,0,925,0,
            new int[]{Color.TRANSPARENT,Color.rgb(81,151,196),Color.TRANSPARENT},
            new float[]{0,.5f,1},Shader.TileMode.CLAMP);
    private Double speed;
    private Integer limit;
    private Integer cameraLimit;
    void radarLimit(Integer value) { cameraLimit = value; invalidate(); }
    private boolean exact;
    private String range, consumption, temperature;
    private View sign, radar, surveillance, menu;
    private static final int WHITE = Color.rgb(242, 246, 250);
    private static final int MUTED = Color.rgb(162, 181, 199);
    private static final int GREEN = Color.rgb(65, 198, 120);
    private static final int ORANGE = Color.rgb(246, 126, 13);

    DrivingDashboardView(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        car = BitmapFactory.decodeResource(getResources(), R.drawable.bmw_e87_hero_v2, options);
        brand = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher_bmw_v2);
        emblemClip.addOval(brandDestination,Path.Direction.CW);
        rim.moveTo(345, 527);
        rim.lineTo(190, 527);
        rim.cubicTo(77, 527, 15, 410, 26, 298);
        rim.cubicTo(40, 143, 175, 53, 302, 61);
        rim.lineTo(407, 63);
        rimMeasure = new PathMeasure(rim, false);
        for (int i=0;i<MARKS.length;i++) {
            float best=Float.MAX_VALUE;
            for(float d=0;d<=rimMeasure.getLength();d+=.25f) {
                rimMeasure.getPosTan(d,point,tangent);
                float dx=point[0]-MARKS[i][0]*1280/1661f;
                float dy=point[1]-MARKS[i][1]*720/922f;
                float error=dx*dx+dy*dy;
                if(error<best) { best=error; divisionDistances[i]=d; }
            }
        }
        offsetPath(band, 17);
        offsetPath(outer, -7);
        for(int i=0;i<luminousStrips.length;i++) luminousStrips[i]=new Path();
        setContentDescription("Cuadro de conducción BMW 118d E87");
    }

    void attach(View sign, View radar, View surveillance, View menu) {
        this.sign = sign; this.radar = radar; this.surveillance = surveillance; this.menu = menu;
        addView(sign); addView(radar); addView(surveillance); addView(menu);
    }

    void readings(Double speed, String range, String consumption, String temperature) {
        this.speed = speed; this.range = range; this.consumption = consumption; this.temperature = temperature;
        invalidate();
    }

    void road(Integer limit, boolean exact) { this.limit = limit; this.exact = exact; invalidate(); }

    void animateInstruments(boolean entering) {
        View[] instruments={sign,radar,surveillance,menu};
        for(int i=0;i<instruments.length;i++) {
            View instrument=instruments[i];
            if(instrument==null) continue;
            instrument.animate().cancel();
            if(!entering) {
                instrument.setAlpha(1f); instrument.setTranslationY(0f);
                instrument.setScaleX(1f); instrument.setScaleY(1f);
                continue;
            }
            instrument.setAlpha(0f); instrument.setTranslationY(20*getResources().getDisplayMetrics().density);
            instrument.setScaleX(.94f); instrument.setScaleY(.94f);
            instrument.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i==0?100:180).setDuration(520)
                    .setInterpolator(new android.view.animation.PathInterpolator(.16f,1f,.3f,1f)).start();
        }
    }

    private void place(View view, int x, int y, int w, int h) {
        float scale = Math.min(getWidth() / 1280f, getHeight() / 720f);
        int left = Math.round((getWidth() - 1280 * scale) / 2 + x * scale);
        int top = Math.round((getHeight() - 720 * scale) / 2 + y * scale);
        int width = Math.round(w * scale), height = Math.round(h * scale);
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        view.layout(left, top, left + width, top + height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (sign == null) return;
        place(sign, 963, 163, 298, 298);
        place(radar, 925, 473, 335, 147);
        place(surveillance, 925, 473, 335, 147);
        place(menu, 1204, 14, 60, 54);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 1280f, getHeight() / 720f);
        canvas.save();
        canvas.translate((getWidth() - 1280 * scale) / 2, (getHeight() - 720 * scale) / 2);
        canvas.scale(scale, scale);
        // Preserve the exact E87 asset, including its floor reflection, behind the instruments.
        if (car != null) canvas.drawBitmap(car, null, new RectF(348, 146, 953, 549), paint);
        drawBrand(canvas);
        text(canvas, limit != null && !exact ? "VELOCIDAD ACONSEJADA" : "LÍMITE DE LA VÍA", 1110, 155, 19, WHITE, false);
        // Shaped base, two luminous contours and a shaded inner track, like the approved image.
        paint.setShader(bandShader);
        stroke(canvas, band, Color.WHITE, 32);
        paint.setShader(null);
        stroke(canvas, outer, Color.rgb(22,64,100), 2);
        stroke(canvas, rim, Color.rgb(18,63,99), 7);
        stroke(canvas, rim, Color.rgb(119,207,255), 3.2f);
        stroke(canvas, rim, WHITE, 1.4f);
        // Subtle blue stippling follows the track, leaving the central display clean.
        paint.setColor(Color.rgb(16,55,79));
        for (float d=0; d<rimMeasure.getLength(); d+=7) {
            rimMeasure.getPosTan(d, point, tangent);
            for(int inset=7;inset<=29;inset+=7)
                canvas.drawCircle(point[0]-tangent[1]*inset,point[1]+tangent[0]*inset,.65f,paint);
        }
        // Keep the expression boxed: a nested numeric conditional would otherwise unbox the
        // nullable road limit before the first GPS/map match and crash the driving view.
        Integer gaugeLimit = limit;
        if (cameraLimit != null) {
            gaugeLimit = exact && limit != null ? Integer.valueOf(Math.min(limit, cameraLimit))
                    : cameraLimit;
        }
        boolean over = DrivingGaugeScale.exceeds(speed, gaugeLimit, cameraLimit != null || exact);
        int active = over ? ORANGE : GREEN;
        if (speed != null && Double.isFinite(speed)) {
            updateLuminousFill(speed);
            // Fade across the ribbon, from a luminous edge into the dark inner track.
            // Cached geometry: no path allocations or reconstruction for unchanged speed.
            int glow = over ? Color.rgb(255,133,8) : Color.rgb(76,255,15);
            for(int i=luminousStrips.length-1;i>=0;i--) {
                float intensity=(float)Math.pow(1f-i/(float)luminousStrips.length,2.3);
                stroke(canvas,luminousStrips[i],Color.argb(Math.round(245*intensity),
                        Color.red(glow),Color.green(glow),Color.blue(glow)),1.7f);
            }
            fill.reset();
            rimMeasure.getSegment(distanceFor(0), distanceFor(speed), fill, true);
            stroke(canvas, fill, Color.argb(28, Color.red(glow), Color.green(glow), Color.blue(glow)), 14);
            stroke(canvas, fill, glow, 2.5f);
            stroke(canvas, fill, over ? Color.rgb(255,235,205) : Color.rgb(232,255,222), 1.2f);
        }
        for (int value = 0; value <= 260; value += 5) {
            rimMeasure.getPosTan(distanceFor(value),point,tangent);
            boolean major = value<=60 && value%20==0 || value>=100 && (value-100)%40==0;
            float length = major ? 23 : value%10==0 ? 13 : 7;
            paint.setColor(WHITE); paint.setStrokeWidth(major ? 4 : .8f);
            canvas.drawLine(point[0],point[1],point[0]-tangent[1]*length,point[1]+tangent[0]*length,paint);
            if (major) {
                int index=0;
                while(DIVISIONS[index]!=value) index++;
                text(canvas,String.valueOf(value),LABELS[index][0]*1280/1661f,
                        LABELS[index][1]*720/922f,27,WHITE,true);
            }
        }
        if ((exact || cameraLimit != null) && gaugeLimit != null) {
            rimMeasure.getPosTan(distanceFor(gaugeLimit),point,tangent);
            paint.setColor(ORANGE); paint.setStrokeWidth(4);
            canvas.drawLine(point[0]+tangent[1]*5,point[1]-tangent[0]*5,
                    point[0]-tangent[1]*28,point[1]+tangent[0]*28,paint);
        }
        text(canvas, speed == null ? "—" : String.format(Locale.getDefault(), "%.0f", speed), 245, 354, 145, speed == null ? WHITE : active, false);
        text(canvas, "km/h", 245, 392, 31, MUTED, false);
        text(canvas, "AUTONOMÍA", 140, 605, 17, MUTED, false);
        text(canvas, range == null ? "—" : range, 140, 657, 37, WHITE, true);
        text(canvas, "CONSUMO", 390, 605, 17, MUTED, false);
        text(canvas, consumption == null ? "—" : consumption, 390, 657, 31, WHITE, true);
        text(canvas, "Temperatura exterior", 733, 605, 17, MUTED, false);
        text(canvas, temperature == null ? "—" : temperature, 733, 651, 30, WHITE, false);
        canvas.restore();
    }

    private void drawBrand(Canvas canvas) {
        // Independent roundel and live type: no rectangular screenshot or visible crop.
        if(brand!=null) {
            canvas.save(); canvas.clipPath(emblemClip);
            canvas.drawBitmap(brand,brandSource,brandDestination,paint); canvas.restore();
        }
        paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(33);
        paint.setColor(WHITE); paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        canvas.drawText("BMW",618,72,paint);
        float next=618+paint.measureText("BMW")+12;
        paint.setTypeface(Typeface.create("sans-serif-light",Typeface.ITALIC));
        canvas.drawText("iDrive",next,72,paint);
        paint.setShader(brandLine); paint.setStrokeWidth(1);
        canvas.drawLine(465,101,925,101,paint); paint.setShader(null);
        text(canvas,"118d  ·  E87",695,125,17,MUTED,false);
    }

    private float distanceFor(double speed) {
        if(!Double.isFinite(speed) || speed<=0) return divisionDistances[0];
        for(int i=1;i<DIVISIONS.length;i++) if(speed<=DIVISIONS[i])
            return divisionDistances[i-1]+(float)((speed-DIVISIONS[i-1])/
                    (DIVISIONS[i]-DIVISIONS[i-1]))*(divisionDistances[i]-divisionDistances[i-1]);
        return divisionDistances[divisionDistances.length-1];
    }
    private void updateLuminousFill(double speed) {
        double clamped=Math.max(0,Math.min(260,speed));
        if(Double.compare(renderedFillSpeed,clamped)==0) return;
        renderedFillSpeed=clamped;
        float start=distanceFor(0),end=distanceFor(clamped);
        int steps=Math.max(1,(int)Math.ceil((end-start)/3));
        for(int i=0;i<luminousStrips.length;i++) {
            Path strip=luminousStrips[i]; strip.reset();
            if(end<=start) continue;
            float inset=2+i;
            for(int j=0;j<=steps;j++) {
                rimMeasure.getPosTan(start+(end-start)*j/steps,point,tangent);
                float x=point[0]-tangent[1]*inset,y=point[1]+tangent[0]*inset;
                if(j==0) strip.moveTo(x,y); else strip.lineTo(x,y);
            }
        }
    }
    private void offsetPath(Path path, float inset) {
        float total = rimMeasure.getLength();
        for(int i=0;i<=200;i++) {
            rimMeasure.getPosTan(total*i/200f,point,tangent);
            float x=point[0]-tangent[1]*inset, y=point[1]+tangent[0]*inset;
            if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
        }
    }
    private void stroke(Canvas c, Path path, int color, float width) {
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
        c.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(size); c.drawText(value, x, y, paint);
    }
}
