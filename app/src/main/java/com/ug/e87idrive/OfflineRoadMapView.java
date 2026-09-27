package com.ug.e87idrive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.location.Location;
import android.view.View;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small offline road-map renderer used only by the driving cluster.
 *
 * <p>The map is deliberately a visual layer. It consumes the local OSM road geometry already
 * stored for speed matching, but it never chooses the displayed speed limit. The arrow remains
 * fixed at the top and the world rotates around it while the car is moving.</n+ */
final class OfflineRoadMapView extends View {
    static final class Road {
        final String osmId;
        final String roadClass;
        final String roadRef;
        final String roadName;
        final int limitKmh;
        final boolean exact;
        /** Alternating latitude/longitude pairs. */
        final double[] points;

        Road(String osmId, String roadClass, String roadRef, int limitKmh, boolean exact,
             double[] points) {
            this(osmId, roadClass, roadRef, "", limitKmh, exact, points);
        }

        Road(String osmId, String roadClass, String roadRef, String roadName, int limitKmh,
             boolean exact, double[] points) {
            this.osmId = osmId == null ? "" : osmId;
            this.roadClass = roadClass == null ? "" : roadClass;
            this.roadRef = roadRef == null ? "" : roadRef;
            this.roadName = roadName == null ? "" : roadName;
            this.limitKmh = limitKmh;
            this.exact = exact;
            this.points = points == null ? new double[0] : points;
        }
    }

    static final class Radar {
        final String id;
        final String type;
        final String road;
        final String source;
        final double latitude;
        final double longitude;
        final Integer limitKmh;

        Radar(String id, String type, String road, String source, double latitude,
              double longitude, Integer limitKmh) {
            this.id = id == null ? "" : id;
            this.type = type == null ? "" : type;
            this.road = road == null ? "" : road;
            this.source = source == null ? "" : source;
            this.latitude = latitude;
            this.longitude = longitude;
            this.limitKmh = limitKmh;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vehiclePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint vehicleHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Bitmap vehicleMarker;
    private final Bitmap vehicleAlpha;
    private List<Road> roads = Collections.emptyList();
    private List<Radar> radars = Collections.emptyList();
    private double latitude;
    private double longitude;
    private float heading;
    private boolean headingReady;
    private boolean moving;
    private boolean hasLocation;
    private String currentRoadId = "";
    private String currentRoadRef = "";
    private String currentRoadName = "";
    private long lastFixAt;

    OfflineRoadMapView(Context context) {
        super(context);
        setWillNotDraw(false);
        vehicleMarker = BitmapFactory.decodeResource(getResources(), R.drawable.e87_map_vehicle_top_v1);
        vehicleAlpha = vehicleMarker == null ? null : vehicleMarker.extractAlpha();
        setContentDescription("Mapa OpenStreetMap offline con posición GPS");
    }

    void setRoads(List<Road> value) {
        roads = value == null ? Collections.emptyList() : value;
        if (currentRoadName.isEmpty()) updateCurrentRoadName();
        invalidate();
    }

    void setRadars(List<Radar> value) {
        radars = value == null ? Collections.emptyList() : value;
        invalidate();
    }

    void setCurrentRoad(String osmId, String roadRef, double roadBearing, Float vehicleBearing) {
        setCurrentRoad(osmId, roadRef, "", roadBearing, vehicleBearing);
    }

    void setCurrentRoad(String osmId, String roadRef, String roadName, double roadBearing,
                        Float vehicleBearing) {
        currentRoadId = osmId == null ? "" : osmId;
        currentRoadRef = roadRef == null ? "" : roadRef;
        currentRoadName = roadName == null ? "" : roadName.trim();
        if (currentRoadName.isEmpty()) updateCurrentRoadName();
        if (moving && Double.isFinite(roadBearing)) {
            float target = normalize((float) roadBearing);
            if (vehicleBearing != null && Float.isFinite(vehicleBearing)
                    && angularDifference(target, vehicleBearing) > 90f) {
                target = normalize(target + 180f);
            }
            heading = headingReady ? interpolateBearing(heading, target, .62f) : target;
            headingReady = true;
        }
        invalidate();
    }

    private void updateCurrentRoadName() {
        currentRoadName = "";
        if (currentRoadId.isEmpty()) return;
        for (Road road : roads) {
            if (currentRoadId.equals(road.osmId)) {
                currentRoadName = road.roadName;
                return;
            }
        }
    }

    /** Updates the camera without allowing a stationary GPS fix to spin the map. */
    void setLocation(Location location, Double speedKmh) {
        if (location == null) return;
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        hasLocation = true;
        double kmh = speedKmh != null && Double.isFinite(speedKmh)
                ? Math.max(0d, speedKmh)
                : location.hasSpeed() ? Math.max(0d, location.getSpeed() * 3.6d) : 0d;
        moving = kmh >= 5d;
        if (moving && location.hasBearing() && Float.isFinite(location.getBearing())) {
            float target = normalize(location.getBearing());
            if (!headingReady) {
                heading = target;
                headingReady = true;
            } else {
                // A short low-pass filter removes the one-frame GNSS jumps without making a
                // roundabout feel delayed. Interpolation is circular at the 0/360 boundary.
                heading = interpolateBearing(heading, target, .24f);
            }
        }
        lastFixAt = System.currentTimeMillis();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        if (hasLocation) {
            drawRoads(canvas, width, height);
            drawRoadLabels(canvas, width, height);
            drawRadars(canvas, width, height);
        } else {
            drawWaitingState(canvas, width, height);
        }
        drawEdgeFade(canvas, width, height);
        drawPositionArrow(canvas, width, height);
        drawLabels(canvas, width, height);
    }

    private void drawRoads(Canvas canvas, int width, int height) {
        // The central window shows roughly 420 m vertically. This is deliberately closer than
        // a full navigation overview so road geometry and numbered routes remain readable.
        final float pixelsPerMeter = Math.min(width, height) / 420f;
        final float metersPerDegreeLon = (float) (111_320d * Math.cos(Math.toRadians(latitude)));
        canvas.save();
        if (headingReady && moving) canvas.rotate(-heading, width / 2f, height / 2f);
        for (Road road : roads) {
            if (road.points.length < 4) continue;
            path.reset();
            boolean first = true;
            for (int i = 0; i + 1 < road.points.length; i += 2) {
                double pointLat = road.points[i];
                double pointLon = road.points[i + 1];
                float east = (float) ((pointLon - longitude) * metersPerDegreeLon);
                float north = (float) ((pointLat - latitude) * 111_320d);
                float x = width / 2f + east * pixelsPerMeter;
                float y = height / 2f - north * pixelsPerMeter;
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
            }
            if (first) continue;
            boolean current = !currentRoadId.isEmpty() && currentRoadId.equals(road.osmId);
            int color = roadColor(road, current);
            float widthPx = current ? 5.2f : roadWidth(road);
            roadPaint.setStyle(Paint.Style.STROKE);
            roadPaint.setStrokeCap(Paint.Cap.ROUND);
            roadPaint.setStrokeJoin(Paint.Join.ROUND);
            // A dark under-stroke keeps the map legible over the dashboard black background.
            roadPaint.setColor(Color.argb(current ? 150 : 110, 0, 0, 0));
            roadPaint.setStrokeWidth(widthPx + 3.2f);
            canvas.drawPath(path, roadPaint);
            roadPaint.setColor(color);
            roadPaint.setStrokeWidth(widthPx);
            canvas.drawPath(path, roadPaint);
        }
        canvas.restore();
    }

    private void drawRoadLabels(Canvas canvas, int width, int height) {
        if (roads.isEmpty()) return;
        final float pixelsPerMeter = Math.min(width, height) / 420f;
        final float metersPerDegreeLon = (float) (111_320d * Math.cos(Math.toRadians(latitude)));
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        labelPaint.setTextSize(Math.max(11f, Math.min(17f, width / 34f)));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setShadowLayer(2.2f, 0f, 1f, Color.BLACK);
        Set<String> shownLabels = new HashSet<>();
        int labels = 0;
        for (Road road : roads) {
            // The current road is already shown in the fixed header. Avoid drawing a long name
            // underneath the vehicle where the marker could cover the middle of the text.
            if (!currentRoadId.isEmpty() && currentRoadId.equals(road.osmId)) continue;
            String roadLabel = road.roadName.isEmpty() ? road.roadRef : road.roadName;
            if (roadLabel.isEmpty() || road.points.length < 4 || labels >= 7) continue;
            roadLabel = compactRoadLabel(roadLabel);
            if (!shownLabels.add(roadLabel)) continue;
            // Long OSM ways can span several kilometres. The midpoint is often outside the
            // compact driving window, so anchor the label to the geometry vertex nearest to
            // the current fix instead.
            int nearest = 0;
            float nearestDistance = Float.MAX_VALUE;
            for (int i = 0; i + 1 < road.points.length; i += 2) {
                float candidateEast = (float) ((road.points[i + 1] - longitude) * metersPerDegreeLon);
                float candidateNorth = (float) ((road.points[i] - latitude) * 111_320d);
                float candidateDistance = candidateEast * candidateEast
                        + candidateNorth * candidateNorth;
                if (candidateDistance < nearestDistance) {
                    nearestDistance = candidateDistance;
                    nearest = i;
                }
            }
            float east = (float) ((road.points[nearest + 1] - longitude) * metersPerDegreeLon);
            float north = (float) ((road.points[nearest] - latitude) * 111_320d);
            float x = width / 2f + east * pixelsPerMeter;
            float y = height / 2f - north * pixelsPerMeter;
            float roadBearing = nearbyRoadBearing(road, nearest);
            // The map rotates below the fixed vehicle. Rotate the label position with it and
            // align the name to the road, but clamp the text to the readable half-turn so it
            // never appears upside down when a horizontal road is viewed from the other side.
            if (headingReady && moving) {
                double angle = Math.toRadians(-heading);
                float dx = x - width / 2f;
                float dy = y - height / 2f;
                float rotatedX = width / 2f + (float) (dx * Math.cos(angle) - dy * Math.sin(angle));
                float rotatedY = height / 2f + (float) (dx * Math.sin(angle) + dy * Math.cos(angle));
                x = rotatedX;
                y = rotatedY;
            }
            if (x < 25f || x > width - 25f || y < 34f || y > height - 28f) continue;
            labelPaint.setColor(Color.argb(210, 192, 223, 239));
            float labelAngle = roadBearing - 90f - (headingReady && moving ? heading : 0f);
            while (labelAngle > 180f) labelAngle -= 360f;
            while (labelAngle < -180f) labelAngle += 360f;
            if (labelAngle > 90f) labelAngle -= 180f;
            if (labelAngle < -90f) labelAngle += 180f;
            canvas.save();
            canvas.rotate(labelAngle, x, y);
            canvas.drawText(roadLabel, x, y, labelPaint);
            canvas.restore();
            labels++;
        }
        labelPaint.clearShadowLayer();
    }

    private static float nearbyRoadBearing(Road road, int nearest) {
        if (road.points.length < 4) return 0f;
        int start = Math.max(0, nearest - 2);
        int end = Math.min(road.points.length - 2, nearest + 2);
        if (start == end) {
            end = Math.min(road.points.length - 2, start + 2);
        }
        if (start == end) return 0f;
        double meanLat = Math.toRadians((road.points[start] + road.points[end]) * .5d);
        double north = (road.points[end] - road.points[start]) * 110_540d;
        double east = (road.points[end + 1] - road.points[start + 1]) * 111_320d
                * Math.cos(meanLat);
        float result = (float) Math.toDegrees(Math.atan2(east, north));
        return normalize(result);
    }

    private void drawRadars(Canvas canvas, int width, int height) {
        if (radars.isEmpty()) return;
        final float pixelsPerMeter = Math.min(width, height) / 420f;
        final float metersPerDegreeLon = (float) (111_320d * Math.cos(Math.toRadians(latitude)));
        canvas.save();
        if (headingReady && moving) canvas.rotate(-heading, width / 2f, height / 2f);
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        labelPaint.setTextSize(Math.max(8f, Math.min(11f, width / 55f)));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        int drawn = 0;
        for (Radar radar : radars) {
            float east = (float) ((radar.longitude - longitude) * metersPerDegreeLon);
            float north = (float) ((radar.latitude - latitude) * 111_320d);
            float x = width / 2f + east * pixelsPerMeter;
            float y = height / 2f - north * pixelsPerMeter;
            if (x < 18f || x > width - 18f || y < 22f || y > height - 22f) continue;
            // Small red camera marker: it is visible on the map without competing with the
            // vehicle marker or turning the whole map into an alert panel.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(80, 241, 45, 45));
            canvas.drawCircle(x, y, 11f, paint);
            paint.setColor(Color.rgb(224, 42, 42));
            canvas.drawCircle(x, y, 6.5f, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, 2.2f, paint);
            labelPaint.setColor(Color.rgb(255, 173, 104));
            canvas.drawText("R", x, y - 10f, labelPaint);
            if (++drawn >= 24) break;
        }
        canvas.restore();
    }

    private static String compactRoadLabel(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 24) return normalized;
        return normalized.substring(0, 23).trim() + "…";
    }

    /** Blends the map into the dashboard so its rectangular child bounds are not visible. */
    private void drawEdgeFade(Canvas canvas, int width, int height) {
        final int fade = Math.max(30, Math.round(Math.min(width, height) * .12f));
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, fade,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, fade, paint);
        paint.setShader(new LinearGradient(0, height - fade, 0, height,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        canvas.drawRect(0, height - fade, width, height, paint);
        paint.setShader(new LinearGradient(0, 0, fade, 0,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, fade, height, paint);
        paint.setShader(new LinearGradient(width - fade, 0, width, 0,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        canvas.drawRect(width - fade, 0, width, height, paint);
        paint.setShader(null);
    }

    private int roadColor(Road road, boolean current) {
        if (current) return Color.rgb(79, 195, 247);
        String type = road.roadClass.toLowerCase(Locale.ROOT);
        if ("motorway".equals(type)) return Color.rgb(42, 130, 182);
        if ("trunk".equals(type) || "primary".equals(type)) return Color.rgb(50, 112, 150);
        if ("secondary".equals(type) || "tertiary".equals(type)) return Color.rgb(40, 85, 117);
        return Color.rgb(32, 68, 94);
    }

    private float roadWidth(Road road) {
        String type = road.roadClass.toLowerCase(Locale.ROOT);
        if ("motorway".equals(type) || "trunk".equals(type)) return 3.8f;
        if ("primary".equals(type) || "secondary".equals(type)) return 3.0f;
        return 1.9f;
    }

    private void drawPositionArrow(Canvas canvas, int width, int height) {
        if (vehicleMarker != null && !vehicleMarker.isRecycled()) {
            drawVehicleMarker(canvas, width, height);
            return;
        }

        float cx = width / 2f;
        float cy = height / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(70, 47, 178, 255));
        canvas.drawCircle(cx, cy, 20f, paint);
        Path arrow = new Path();
        arrow.moveTo(cx, cy - 17f);
        arrow.lineTo(cx - 10f, cy + 12f);
        arrow.lineTo(cx, cy + 7f);
        arrow.lineTo(cx + 10f, cy + 12f);
        arrow.close();
        paint.setColor(Color.rgb(67, 190, 245));
        canvas.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(Color.WHITE);
        canvas.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Keeps the vehicle upright while the map rotates below it. */
    private void drawVehicleMarker(Canvas canvas, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f;
        float markerHeight = Math.max(56f, Math.min(82f, height * .18f));
        float markerWidth = markerHeight * vehicleMarker.getWidth() / vehicleMarker.getHeight();
        RectF destination = new RectF(
                cx - markerWidth / 2f,
                cy - markerHeight / 2f,
                cx + markerWidth / 2f,
                cy + markerHeight / 2f);

        if (vehicleAlpha != null) {
            RectF halo = new RectF(destination);
            halo.inset(-1.5f, -1.5f);
            vehicleHaloPaint.setAlpha(105);
            vehicleHaloPaint.setColorFilter(new PorterDuffColorFilter(Color.WHITE,
                    PorterDuff.Mode.SRC_IN));
            vehicleHaloPaint.setMaskFilter(new BlurMaskFilter(2.4f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawBitmap(vehicleAlpha, null, halo, vehicleHaloPaint);
            vehicleHaloPaint.setMaskFilter(null);
            vehicleHaloPaint.setColorFilter(null);
            vehicleHaloPaint.setAlpha(255);
        }
        canvas.drawBitmap(vehicleMarker, null, destination, vehiclePaint);
    }

    private void drawLabels(Canvas canvas, int width, int height) {
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        labelPaint.setTextSize(Math.max(11f, Math.min(16f, width / 36f)));
        labelPaint.setColor(Color.rgb(166, 201, 223));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        String currentRoadLabel = !currentRoadName.isEmpty()
                ? compactRoadLabel(currentRoadName) : currentRoadRef;
        if (!currentRoadLabel.isEmpty()) {
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            labelPaint.setShadowLayer(2.2f, 0f, 1f, Color.BLACK);
            canvas.drawText(currentRoadLabel, width - 13f, 23f, labelPaint);
            labelPaint.clearShadowLayer();
        }
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        // OSM attribution remains visible as required, but should not compete with the driving UI.
        labelPaint.setTextSize(Math.max(8f, Math.min(8.5f, width / 72f)));
        labelPaint.setColor(Color.rgb(126, 156, 177));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("© OpenStreetMap", width - 6f, height - 6f, labelPaint);
    }

    private void drawWaitingState(Canvas canvas, int width, int height) {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        labelPaint.setTextSize(17f);
        labelPaint.setColor(Color.rgb(166, 201, 223));
        canvas.drawText("MAPA OSM · OFFLINE", width / 2f, height / 2f - 8f, labelPaint);
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        labelPaint.setTextSize(12f);
        labelPaint.setColor(Color.rgb(126, 156, 177));
        canvas.drawText("Esperando posición GPS", width / 2f, height / 2f + 16f, labelPaint);
    }

    static float normalize(float value) {
        float result = value % 360f;
        return result < 0f ? result + 360f : result;
    }

    static float interpolateBearing(float from, float to, float amount) {
        float delta = ((to - from + 540f) % 360f) - 180f;
        return normalize(from + delta * amount);
    }

    private static float angularDifference(float first, float second) {
        float delta = Math.abs(normalize(first) - normalize(second));
        return delta > 180f ? 360f - delta : delta;
    }
}
