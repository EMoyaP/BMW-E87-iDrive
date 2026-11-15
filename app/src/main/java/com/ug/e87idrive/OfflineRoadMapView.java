package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.location.Location;
import android.view.View;

import java.util.Collections;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small offline road-map renderer used only by the driving cluster.
 *
 * <p>The map is deliberately a visual layer. It consumes the local OSM road geometry already
 * stored for speed matching, but it never chooses the displayed speed limit. The arrow remains
 * fixed at the top and the world rotates around it while the car is moving.
 */
final class OfflineRoadMapView extends View {
    /** A map position is useful only while it still represents the current vehicle location. */
    private static final long MAX_LOCATION_AGE_MS = 15_000L;
    private static final String MAP_PREFERENCES = "driving_map";
    private static final String KEY_ZOOM = "zoom_scale";
    /** Navigation-style camera: more map remains visible in front of the vehicle. */
    private static final float VEHICLE_ANCHOR_Y_RATIO = .72f;
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
        /** Verified enforcement headings in true degrees; may contain both carriageway headings. */
        final float[] directionBearings;
        /** Direction is explicitly bidirectional even when the road axis must be inferred locally. */
        final boolean bidirectional;

        Radar(String id, String type, String road, String source, double latitude,
              double longitude, Integer limitKmh, Float directionBearing) {
            this(id, type, road, source, latitude, longitude, limitKmh,
                    directionBearing == null ? new float[0] : new float[]{directionBearing}, false);
        }

        Radar(String id, String type, String road, String source, double latitude,
              double longitude, Integer limitKmh, float[] directionBearings,
              boolean bidirectional) {
            this.id = id == null ? "" : id;
            this.type = type == null ? "" : type;
            this.road = road == null ? "" : road;
            this.source = source == null ? "" : source;
            this.latitude = latitude;
            this.longitude = longitude;
            this.limitKmh = limitKmh;
            this.directionBearings = directionBearings == null ? new float[0]
                    : java.util.Arrays.copyOf(directionBearings, directionBearings.length);
            this.bidirectional = bidirectional;
        }
    }

    static final class FuelStation {
        final String id;
        final double latitude;
        final double longitude;
        final double price;
        final boolean cheapest;

        FuelStation(String id, double latitude, double longitude, double price,
                    boolean cheapest) {
            this.id = id == null ? "" : id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.price = price;
            this.cheapest = cheapest;
        }
    }

    static final class Incident {
        final String id;
        final String title;
        final String road;
        final int severity;
        final double[] points;
        Incident(String id, String title, String road, int severity, double[] points) {
            this.id=id==null?"":id; this.title=title==null?"":title;
            this.road=road==null?"":road; this.severity=severity;
            this.points=points==null?new double[0]:points;
        }
    }

    static final class TrafficFlow {
        final int currentSpeedKmh;
        final int freeFlowSpeedKmh;
        final double confidence;
        final boolean closed;
        final double[] points;
        final long fetchedAt;

        TrafficFlow(int currentSpeedKmh, int freeFlowSpeedKmh, double confidence,
                    boolean closed, double[] points, long fetchedAt) {
            this.currentSpeedKmh = currentSpeedKmh;
            this.freeFlowSpeedKmh = freeFlowSpeedKmh;
            this.confidence = confidence;
            this.closed = closed;
            this.points = points == null ? new double[0] : points;
            this.fetchedAt = fetchedAt;
        }
    }

    private static final class RoadLabelCandidate {
        final String text;
        final int priority;
        final float textSize;
        final float x;
        final float y;
        final float angle;
        final float score;
        final RectF bounds;

        RoadLabelCandidate(String text, int priority, float textSize, float x, float y,
                           float angle, float score, RectF bounds) {
            this.text = text;
            this.priority = priority;
            this.textSize = textSize;
            this.x = x;
            this.y = y;
            this.angle = angle;
            this.score = score;
            this.bounds = bounds;
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
    private final SharedPreferences mapPreferences;
    private List<Road> roads = Collections.emptyList();
    private List<Radar> radars = Collections.emptyList();
    private List<FuelStation> fuelStations = Collections.emptyList();
    private List<Incident> incidents = Collections.emptyList();
    private List<TrafficFlow> trafficFlows = Collections.emptyList();
    private final Map<String, List<Road>> roadConnections = new HashMap<>();
    private final Map<String, List<Road>> incidentRoadPaths = new HashMap<>();
    private double latitude;
    private double longitude;
    private double renderLatitude;
    private double renderLongitude;
    private float locationAccuracyMeters = 20f;
    private float heading;
    private boolean headingReady;
    private boolean moving;
    private boolean hasLocation;
    private Float gpsBearing;
    private Location previousMapLocation;
    private long previousMapLocationAt;
    private String currentRoadId = "";
    private String visualRoadId = "";
    private String radarAlertId = "";
    private String currentRoadRef = "";
    private String currentRoadName = "";
    private long lastFixAt;
    private float zoomScale;

    OfflineRoadMapView(Context context) {
        super(context);
        setWillNotDraw(false);
        vehicleMarker = BitmapFactory.decodeResource(getResources(), R.drawable.e87_map_vehicle_top_v1);
        vehicleAlpha = vehicleMarker == null ? null : vehicleMarker.extractAlpha();
        mapPreferences = context.getApplicationContext()
                .getSharedPreferences(MAP_PREFERENCES, Context.MODE_PRIVATE);
        zoomScale = Math.max(.70f, Math.min(1.85f, mapPreferences.getFloat(KEY_ZOOM, 1f)));
        setContentDescription("Mapa OpenStreetMap offline con posición GPS");
    }

    void setRoads(List<Road> value) {
        roads = value == null ? Collections.emptyList() : value;
        roadConnections.clear();
        incidentRoadPaths.clear();
        if (currentRoadName.isEmpty()) updateCurrentRoadName();
        invalidate();
    }

    void setRadars(List<Radar> value) {
        radars = value == null ? Collections.emptyList() : value;
        invalidate();
    }

    void setRadarAlertId(String value) {
        radarAlertId = value == null ? "" : value;
        invalidate();
    }

    void setFuelStations(List<FuelStation> value) {
        fuelStations = value == null ? Collections.emptyList() : value;
        invalidate();
    }

    void setIncidents(List<Incident> value) {
        incidents=value==null?Collections.emptyList():value;
        invalidate();
    }

    void setTrafficFlows(List<TrafficFlow> value) {
        trafficFlows = value == null ? Collections.emptyList() : value;
        invalidate();
    }

    void adjustZoom(float factor) {
        if (!Float.isFinite(factor) || factor <= 0f) return;
        zoomScale = Math.max(.70f, Math.min(1.85f, zoomScale * factor));
        mapPreferences.edit().putFloat(KEY_ZOOM, zoomScale).apply();
        invalidate();
    }

    void resetZoom() {
        zoomScale = 1f;
        mapPreferences.edit().putFloat(KEY_ZOOM, zoomScale).apply();
        invalidate();
    }

    void setCurrentRoad(String osmId, String roadRef, double roadBearing, Float vehicleBearing) {
        setCurrentRoad(osmId, roadRef, "", roadBearing, vehicleBearing);
    }

    void setCurrentRoad(String osmId, String roadRef, String roadName, double roadBearing,
                        Float vehicleBearing) {
        String nextRoadId = osmId == null ? "" : osmId;
        if (!nextRoadId.equals(currentRoadId)) incidentRoadPaths.clear();
        currentRoadId = nextRoadId;
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

    /** Updates the camera. A known heading remains valid at a brief stop so north never jumps. */
    void setLocation(Location location, Double speedKmh) {
        if (location == null) {
            hasLocation = false;
            moving = false;
            invalidate();
            return;
        }
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        gpsBearing = location.hasBearing() && Float.isFinite(location.getBearing())
                ? normalize(location.getBearing()) : null;
        locationAccuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 20f;
        hasLocation = true;
        long now = System.currentTimeMillis();
        double kmh = speedKmh != null && Double.isFinite(speedKmh)
                ? Math.max(0d, speedKmh)
                : location.hasSpeed() ? Math.max(0d, location.getSpeed() * 3.6d) : 0d;
        float course = gpsBearing == null ? Float.NaN : gpsBearing;
        boolean inferredMotion = false;
        if (previousMapLocation != null) {
            long elapsed = now - previousMapLocationAt;
            float moved = previousMapLocation.distanceTo(location);
            if (elapsed >= 500L && elapsed <= 15_000L && moved >= 4f && moved <= 300f) {
                inferredMotion = moved / (elapsed / 1_000f) * 3.6f
                        <= GpsSpeedProvider.MAX_PLAUSIBLE_SPEED_KMH;
                if (inferredMotion && !Float.isFinite(course)) {
                    course = previousMapLocation.bearingTo(location);
                }
            }
        }
        previousMapLocation = new Location(location);
        previousMapLocationAt = now;
        // Keep heading-up and centreline snapping usable when this receiver omits its speed
        // field. A teleport or impossible mock jump is rejected by the bounded displacement.
        moving = kmh >= 5d || inferredMotion;
        if (moving && Float.isFinite(course)) {
            float target = normalize(course);
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

        if (hasFreshLocation()) {
            updateRenderOrigin();
            drawRoads(canvas, width, height);
            drawTrafficFlows(canvas, width, height);
            drawIncidents(canvas, width, height);
            drawRoadLabels(canvas, width, height);
            drawFuelStations(canvas, width, height);
            drawRadars(canvas, width, height);
        } else {
            drawWaitingState(canvas, width, height);
        }
        drawEdgeFade(canvas, width, height);
        drawPositionArrow(canvas, width, height);
        drawLabels(canvas, width, height);
        // A GPS loss does not necessarily trigger another View invalidation. Re-check shortly
        // so an old fix cannot leave a frozen map/limit context visible indefinitely.
        if (hasFreshLocation()) postInvalidateDelayed(1_000L);
    }

    private boolean hasFreshLocation() {
        return hasLocation && lastFixAt > 0L
                && System.currentTimeMillis() - lastFixAt <= MAX_LOCATION_AGE_MS;
    }

    /**
     * Snaps only the visual camera origin to the selected OSM centreline. The repositories keep
     * receiving and evaluating the unmodified GPS fix. This is the same presentation principle
     * used by navigation maps: the vehicle remains centred on its matched road despite ordinary
     * lateral GNSS error.
     */
    private void updateRenderOrigin() {
        renderLatitude = latitude;
        renderLongitude = longitude;
        visualRoadId = currentRoadId;
        if (roads.isEmpty()) return;
        RoadProjection selected = null;
        Road selectedRoad = null;
        double bestScore = Double.MAX_VALUE;
        Float reference = gpsBearing != null ? gpsBearing : headingReady ? heading : null;

        // First retain the road selected by the speed matcher. If it has no limit (common on
        // roundabout rings and connector links), visually map-match against every local road so
        // the car reference points still sit on the road instead of drifting to raw GNSS.
        for (Road road : roads) {
            if (road.points.length < 4) continue;
            boolean selectedBySpeed = !currentRoadId.isEmpty()
                    && currentRoadId.equals(road.osmId);
            if (!currentRoadId.isEmpty() && !selectedBySpeed) continue;
            RoadProjection projection = projectOnRoad(road, latitude, longitude);
            if (projection == null) continue;
            double directionDifference = reference == null ? 0d
                    : axisDifference(reference, projection.bearing);
            if (moving && reference != null && directionDifference > 58d) continue;
            double score = projection.distanceMeters + directionDifference * .35d;
            if (selected == null || score < bestScore) {
                selected = projection;
                selectedRoad = road;
                bestScore = score;
            }
        }

        double tolerance = Math.max(12d, Math.min(35d, locationAccuracyMeters * 2d));
        // The matched speed way may exist in SQLite but be absent from this visual window, or
        // its clipped geometry may not reach the current fix. In either case, fall back to the
        // nearest heading-compatible road actually drawn on the map. Without the distance check
        // the car could stay at raw GNSS while a different nearby line is visibly under it.
        if (!currentRoadId.isEmpty()
                && (selected == null || selected.distanceMeters > tolerance)) {
            selected = null;
            selectedRoad = null;
            bestScore = Double.MAX_VALUE;
            for (Road road : roads) {
                if (road.points.length < 4) continue;
                RoadProjection projection = projectOnRoad(road, latitude, longitude);
                if (projection == null) continue;
                double directionDifference = reference == null ? 0d
                        : axisDifference(reference, projection.bearing);
                if (moving && reference != null && directionDifference > 58d) continue;
                double score = projection.distanceMeters + directionDifference * .35d;
                if (selected == null || score < bestScore) {
                    selected = projection;
                    selectedRoad = road;
                    bestScore = score;
                }
            }
        }

        if (selected == null || selected.distanceMeters > tolerance
                || selectedRoad == null) {
            visualRoadId = "";
            return;
        }
        renderLatitude = selected.latitude;
        renderLongitude = selected.longitude;
        visualRoadId = selectedRoad.osmId;
        if (moving && Double.isFinite(selected.bearing)) {
            float target = normalize((float) selected.bearing);
            if (reference != null && angularDifference(target, reference) > 90f) {
                target = normalize(target + 180f);
            }
            heading = headingReady ? interpolateBearing(heading, target, .42f) : target;
            headingReady = true;
        }
    }

    private static final class RoadProjection {
        final double latitude, longitude, distanceMeters, bearing;
        RoadProjection(double latitude, double longitude, double distanceMeters, double bearing) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = distanceMeters;
            this.bearing = bearing;
        }
    }

    private static RoadProjection projectOnRoad(Road road, double latitude, double longitude) {
        double metersPerDegreeLon = 111_320d * Math.cos(Math.toRadians(latitude));
        double bestDistance = Double.MAX_VALUE, bestLat = latitude, bestLon = longitude;
        double bestBearing = Double.NaN;
        for (int i = 0; i + 3 < road.points.length; i += 2) {
            double firstEast = (road.points[i + 1] - longitude) * metersPerDegreeLon;
            double firstNorth = (road.points[i] - latitude) * 111_320d;
            double secondEast = (road.points[i + 3] - longitude) * metersPerDegreeLon;
            double secondNorth = (road.points[i + 2] - latitude) * 111_320d;
            double east = secondEast - firstEast, north = secondNorth - firstNorth;
            double squaredLength = east * east + north * north;
            if (squaredLength < .01d) continue;
            double fraction = Math.max(0d, Math.min(1d,
                    -(firstEast * east + firstNorth * north) / squaredLength));
            double projectedEast = firstEast + fraction * east;
            double projectedNorth = firstNorth + fraction * north;
            double distance = Math.hypot(projectedEast, projectedNorth);
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            bestLat = latitude + projectedNorth / 111_320d;
            bestLon = longitude + projectedEast / Math.max(1d, metersPerDegreeLon);
            bestBearing = Math.toDegrees(Math.atan2(east, north));
            if (bestBearing < 0d) bestBearing += 360d;
        }
        return bestDistance == Double.MAX_VALUE ? null
                : new RoadProjection(bestLat, bestLon, bestDistance, bestBearing);
    }

    private static double axisDifference(double first, double second) {
        double difference = Math.abs(first - second) % 360d;
        if (difference > 180d) difference = 360d - difference;
        return Math.min(difference, 180d - difference);
    }

    private void drawRoads(Canvas canvas, int width, int height) {
        // The central window shows roughly 420 m vertically. This is deliberately closer than
        // a full navigation overview so road geometry and numbered routes remain readable.
        final float pixelsPerMeter = mapPixelsPerMeter(width, height);
        final float metersPerDegreeLon = (float) (111_320d * Math.cos(Math.toRadians(renderLatitude)));
        float anchorX = vehicleAnchorX(width);
        float anchorY = vehicleAnchorY(height);
        canvas.save();
        if (headingReady) canvas.rotate(-heading, anchorX, anchorY);
        for (Road road : roads) {
            if (road.points.length < 4) continue;
            path.reset();
            boolean first = true;
            for (int i = 0; i + 1 < road.points.length; i += 2) {
                double pointLat = road.points[i];
                double pointLon = road.points[i + 1];
                float east = (float) ((pointLon - renderLongitude) * metersPerDegreeLon);
                float north = (float) ((pointLat - renderLatitude) * 111_320d);
                float x = anchorX + east * pixelsPerMeter;
                float y = anchorY - north * pixelsPerMeter;
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
            }
            if (first) continue;
            boolean current = !visualRoadId.isEmpty() && visualRoadId.equals(road.osmId);
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
        final float pixelsPerMeter = mapPixelsPerMeter(width, height);
        final float metersPerDegreeLon = (float) (111_320d * Math.cos(Math.toRadians(renderLatitude)));
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setShadowLayer(2.6f, 0f, 0f, Color.BLACK);
        ArrayList<RoadLabelCandidate> candidates = new ArrayList<>();
        for (Road road : roads) {
            // The current road is already shown in the fixed header. Avoid drawing a long name
            // underneath the vehicle where the marker could cover the middle of the text.
            if (!currentRoadId.isEmpty() && currentRoadId.equals(road.osmId)) continue;
            String roadLabel = road.roadName.isEmpty() ? road.roadRef : road.roadName;
            if (roadLabel.isEmpty() || road.points.length < 4) continue;
            roadLabel = compactRoadLabel(roadLabel);
            int priority = roadLabelPriority(road);
            float textSize = roadLabelTextSize(width, priority);
            labelPaint.setTextSize(textSize);
            float textWidth = labelPaint.measureText(roadLabel);
            RoadLabelCandidate candidate = createRoadLabelCandidate(roadLabel, road, priority,
                    textSize, textWidth, width, height, pixelsPerMeter, metersPerDegreeLon);
            if (candidate != null) candidates.add(candidate);
        }
        Collections.sort(candidates, (left, right) -> {
            int priorityOrder = Integer.compare(left.priority, right.priority);
            return priorityOrder != 0 ? priorityOrder : Float.compare(left.score, right.score);
        });
        Set<String> shownLabels = new HashSet<>();
        ArrayList<RectF> occupied = roadLabelExclusions(width, height, pixelsPerMeter,
                metersPerDegreeLon);
        int labels = 0;
        for (RoadLabelCandidate candidate : candidates) {
            if (labels >= 5 || shownLabels.contains(candidate.text)
                    || intersectsAny(candidate.bounds, occupied)) continue;
            shownLabels.add(candidate.text);
            occupied.add(candidate.bounds);
            labelPaint.setTextSize(candidate.textSize);
            labelPaint.setColor(Color.argb(candidate.priority <= 2 ? 235 : 205,
                    192, 223, 239));
            canvas.save();
            canvas.rotate(candidate.angle, candidate.x, candidate.y);
            canvas.drawText(candidate.text, candidate.x,
                    candidate.y + candidate.textSize * .34f, labelPaint);
            canvas.restore();
            labels++;
        }
        labelPaint.clearShadowLayer();
    }

    private void drawIncidents(Canvas canvas,int width,int height){
        if(incidents.isEmpty())return;
        final float pixelsPerMeter=Math.min(width,height)/420f*zoomScale;
        final float metersPerDegreeLon=(float)(111_320d*Math.cos(Math.toRadians(renderLatitude)));
        float anchorX=vehicleAnchorX(width),anchorY=vehicleAnchorY(height);
        canvas.save();if(headingReady)canvas.rotate(-heading,anchorX,anchorY);
        roadPaint.setStyle(Paint.Style.STROKE);roadPaint.setStrokeCap(Paint.Cap.ROUND);
        roadPaint.setStrokeJoin(Paint.Join.ROUND);
        for(Incident incident:incidents){
            if(incident.points.length<2)continue;
            int tone=incident.severity==TrafficIncidentRepository.RED
                    ?Color.rgb(226,48,48):Color.rgb(247,151,30);
            if(incident.points.length==2){
                float east=(float)((incident.points[1]-renderLongitude)*metersPerDegreeLon);
                float north=(float)((incident.points[0]-renderLatitude)*111_320d);
                float x=anchorX+east*pixelsPerMeter,y=anchorY-north*pixelsPerMeter;
                Road affected=nearestRoad(incident.points[0],incident.points[1],90d,incident.road);
                Road active=roadById(currentRoadId);
                roadPaint.setStyle(Paint.Style.STROKE);
                roadPaint.setColor(Color.argb(200,Color.red(tone),Color.green(tone),Color.blue(tone)));
                roadPaint.setStrokeWidth(10f);
                // Follow the connected OSM road chain from the car's road to the incident road.
                // This colors the approach through a junction even when the obstacle is on a
                // neighboring street, while leaving nearby but disconnected roads untouched.
                List<Road> approach=connectedIncidentApproach(active,affected);
                if(!approach.isEmpty()) {
                    for(Road road:approach)drawRoadGeometry(canvas,road,anchorX,anchorY,
                            pixelsPerMeter,metersPerDegreeLon);
                } else {
                    if(active!=null)drawRoadGeometry(canvas,active,anchorX,anchorY,
                            pixelsPerMeter,metersPerDegreeLon);
                    if(affected!=null&&(active==null||!affected.osmId.equals(active.osmId)))
                        drawRoadGeometry(canvas,affected,anchorX,anchorY,pixelsPerMeter,
                                metersPerDegreeLon);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(80,Color.red(tone),Color.green(tone),Color.blue(tone)));
                canvas.drawCircle(x,y,18f,paint);
                paint.setColor(tone);canvas.drawCircle(x,y,10f,paint);
                labelPaint.setStyle(Paint.Style.FILL);labelPaint.setColor(Color.WHITE);
                labelPaint.setTextAlign(Paint.Align.CENTER);
                labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif",
                        android.graphics.Typeface.BOLD));
                labelPaint.setTextSize(13f);
                String title=incident.title.toUpperCase(Locale.ROOT);
                String glyph=title.contains("CORTADA")?"×":title.contains("ATASCO")?"≋"
                        :title.contains("LENTA")?"≈":title.contains("AVERIA")?"A":"!";
                canvas.drawText(glyph,x,y+4.5f,labelPaint);
                continue;
            }
            path.reset();boolean first=true;
            for(int i=0;i+1<incident.points.length;i+=2){
                float east=(float)((incident.points[i+1]-renderLongitude)*metersPerDegreeLon);
                float north=(float)((incident.points[i]-renderLatitude)*111_320d);
                float x=anchorX+east*pixelsPerMeter,y=anchorY-north*pixelsPerMeter;
                if(first){path.moveTo(x,y);first=false;}else path.lineTo(x,y);
            }
            roadPaint.setColor(Color.argb(75,Color.red(tone),Color.green(tone),Color.blue(tone)));
            roadPaint.setStrokeWidth(14f);canvas.drawPath(path,roadPaint);
            roadPaint.setColor(tone);roadPaint.setStrokeWidth(5.5f);canvas.drawPath(path,roadPaint);
        }
        canvas.restore();
    }

    private Road nearestRoad(double latitude,double longitude,double maxDistanceMeters){
        return nearestRoad(latitude,longitude,maxDistanceMeters,"");
    }

    private Road nearestRoad(double latitude,double longitude,double maxDistanceMeters,
                             String roadIdentity){
        Road best=null;double bestDistance=maxDistanceMeters;
        double metersLon=111_320d*Math.cos(Math.toRadians(latitude));
        String wanted=normalizeRoadIdentity(roadIdentity);
        for(Road road:roads){
            String identity=normalizeRoadIdentity(road.roadRef);
            if(identity.isEmpty())identity=normalizeRoadIdentity(road.roadName);
            if(!wanted.isEmpty()&&!identity.equals(wanted))continue;
            for(int i=0;i+3<road.points.length;i+=2){
                double ax=(road.points[i+1]-longitude)*metersLon;
                double ay=(road.points[i]-latitude)*111_320d;
                double bx=(road.points[i+3]-longitude)*metersLon;
                double by=(road.points[i+2]-latitude)*111_320d;
                double dx=bx-ax,dy=by-ay,len=dx*dx+dy*dy;
                double t=len<=.0001d?0d:Math.max(0d,Math.min(1d,-(ax*dx+ay*dy)/len));
                double distance=Math.hypot(ax+t*dx,ay+t*dy);
                if(distance<bestDistance){bestDistance=distance;best=road;}
            }
        }
        return best;
    }

    private List<Road> connectedIncidentApproach(Road start,Road goal){
        if(start==null)return goal==null?Collections.emptyList():Collections.singletonList(goal);
        if(goal==null)return Collections.singletonList(start);
        String cacheKey=start.osmId+'|'+goal.osmId;
        List<Road> cached=incidentRoadPaths.get(cacheKey);
        if(cached!=null)return cached;
        if(start.osmId.equals(goal.osmId)) {
            List<Road> same=Collections.singletonList(start);incidentRoadPaths.put(cacheKey,same);
            return same;
        }
        ensureRoadConnections();
        ArrayDeque<Road> queue=new ArrayDeque<>();
        HashMap<String,Road> previous=new HashMap<>();
        HashMap<String,Integer> depth=new HashMap<>();
        queue.add(start);previous.put(start.osmId,null);depth.put(start.osmId,0);
        boolean found=false;
        while(!queue.isEmpty()&&!found){
            Road road=queue.removeFirst();int nextDepth=depth.get(road.osmId)+1;
            if(nextDepth>5)continue;
            List<Road> neighbors=roadConnections.get(road.osmId);
            if(neighbors==null)continue;
            for(Road neighbor:neighbors){
                if(previous.containsKey(neighbor.osmId))continue;
                previous.put(neighbor.osmId,road);depth.put(neighbor.osmId,nextDepth);
                if(neighbor.osmId.equals(goal.osmId)){found=true;break;}
                queue.addLast(neighbor);
            }
        }
        ArrayList<Road> result=new ArrayList<>();
        if(found){
            for(Road road=goal;road!=null;road=previous.get(road.osmId))result.add(road);
            Collections.reverse(result);
        }
        List<Road> immutable=Collections.unmodifiableList(result);
        incidentRoadPaths.put(cacheKey,immutable);
        return immutable;
    }

    private void ensureRoadConnections(){
        if(!roadConnections.isEmpty()||roads.isEmpty())return;
        HashMap<String,ArrayList<Road>> endpointBuckets=new HashMap<>();
        for(Road road:roads){
            if(road.points.length<4)continue;
            addEndpointBucket(endpointBuckets,road,0);
            addEndpointBucket(endpointBuckets,road,road.points.length-2);
            roadConnections.put(road.osmId,new ArrayList<>());
        }
        for(Road road:roads){
            if(road.points.length<4)continue;
            connectEndpointNeighbors(road,0,endpointBuckets);
            connectEndpointNeighbors(road,road.points.length-2,endpointBuckets);
        }
    }

    private static void addEndpointBucket(Map<String,ArrayList<Road>> buckets,Road road,int point){
        String key=endpointCell(road.points[point],road.points[point+1]);
        ArrayList<Road> values=buckets.get(key);
        if(values==null){values=new ArrayList<>();buckets.put(key,values);}
        if(!values.contains(road))values.add(road);
    }

    private void connectEndpointNeighbors(Road road,int point,
                                           Map<String,ArrayList<Road>> buckets){
        double lat=road.points[point],lon=road.points[point+1];
        int latCell=(int)Math.floor(lat/0.00025d),lonCell=(int)Math.floor(lon/0.00032d);
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            List<Road> candidates=buckets.get((latCell+dy)+":"+(lonCell+dx));
            if(candidates==null)continue;
            for(Road candidate:candidates){
                if(candidate.osmId.equals(road.osmId))continue;
                if(endpointDistanceMeters(road,point,candidate)<=35d){
                    addRoadConnection(road,candidate);addRoadConnection(candidate,road);
                }
            }
        }
    }

    private void addRoadConnection(Road from,Road to){
        List<Road> neighbors=roadConnections.get(from.osmId);
        if(neighbors!=null&&!neighbors.contains(to))neighbors.add(to);
    }

    private static double endpointDistanceMeters(Road first,int firstPoint,Road second){
        double lat=first.points[firstPoint],lon=first.points[firstPoint+1];
        double best=Double.MAX_VALUE;
        int[] points={0,second.points.length-2};
        for(int point:points){
            double dy=(second.points[point]-lat)*111_320d;
            double dx=(second.points[point+1]-lon)*111_320d
                    *Math.cos(Math.toRadians(lat));
            best=Math.min(best,Math.hypot(dx,dy));
        }
        return best;
    }

    private static String endpointCell(double latitude,double longitude){
        return (int)Math.floor(latitude/0.00025d)+":"
                +(int)Math.floor(longitude/0.00032d);
    }

    private static String normalizeRoadIdentity(String value){
        if(value==null)return "";
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private Road roadById(String id){
        if(id==null||id.isEmpty())return null;
        for(Road road:roads)if(id.equals(road.osmId))return road;
        return null;
    }

    private void drawRoadGeometry(Canvas canvas,Road road,float anchorX,float anchorY,
                                  float pixelsPerMeter,float metersPerDegreeLon){
        if(road.points.length<4)return;
        path.reset();boolean first=true;
        for(int i=0;i+1<road.points.length;i+=2){
            float east=(float)((road.points[i+1]-renderLongitude)*metersPerDegreeLon);
            float north=(float)((road.points[i]-renderLatitude)*111_320d);
            float x=anchorX+east*pixelsPerMeter,y=anchorY-north*pixelsPerMeter;
            if(first){path.moveTo(x,y);first=false;}else path.lineTo(x,y);
        }
        canvas.drawPath(path,roadPaint);
    }

    private void drawTrafficFlows(Canvas canvas, int width, int height) {
        if (trafficFlows.isEmpty()) return;
        final float pixelsPerMeter = mapPixelsPerMeter(width, height);
        final float metersPerDegreeLon = (float) (111_320d
                * Math.cos(Math.toRadians(renderLatitude)));
        float anchorX = vehicleAnchorX(width), anchorY = vehicleAnchorY(height);
        canvas.save();
        if (headingReady) canvas.rotate(-heading, anchorX, anchorY);
        roadPaint.setStyle(Paint.Style.STROKE);
        roadPaint.setStrokeCap(Paint.Cap.ROUND);
        roadPaint.setStrokeJoin(Paint.Join.ROUND);
        for (TrafficFlow flow : trafficFlows) {
            if (flow.points.length < 4) continue;
            path.reset(); boolean first = true;
            for (int i = 0; i + 1 < flow.points.length; i += 2) {
                float east = (float) ((flow.points[i + 1] - renderLongitude)
                        * metersPerDegreeLon);
                float north = (float) ((flow.points[i] - renderLatitude) * 111_320d);
                float x = anchorX + east * pixelsPerMeter;
                float y = anchorY - north * pixelsPerMeter;
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
            }
            if (first) continue;
            double ratio = flow.freeFlowSpeedKmh <= 0 ? 1d
                    : flow.currentSpeedKmh / (double) flow.freeFlowSpeedKmh;
            int tone = flow.closed || ratio < .50d ? Color.rgb(229, 55, 48)
                    : ratio < .80d ? Color.rgb(247, 151, 30)
                    : Color.rgb(45, 201, 103);
            roadPaint.setColor(Color.argb(65, Color.red(tone), Color.green(tone),
                    Color.blue(tone)));
            roadPaint.setStrokeWidth(12f);
            canvas.drawPath(path, roadPaint);
            roadPaint.setColor(Color.argb(235, Color.red(tone), Color.green(tone),
                    Color.blue(tone)));
            roadPaint.setStrokeWidth(4.5f);
            canvas.drawPath(path, roadPaint);
        }
        canvas.restore();
    }

    private ArrayList<RectF> roadLabelExclusions(int width, int height, float pixelsPerMeter,
                                                  float metersPerDegreeLon) {
        ArrayList<RectF> occupied = new ArrayList<>();
        // Vehicle, zoom controls and Android Auto shortcut are fixed UI elements.
        float anchorX = vehicleAnchorX(width);
        float anchorY = vehicleAnchorY(height);
        occupied.add(new RectF(anchorX - 72f, anchorY - 74f,
                anchorX + 72f, anchorY + 74f));
        occupied.add(new RectF(width - 118f, height - 72f, width, height));
        for (FuelStation station : fuelStations) {
            float[] point = roadPointToScreen(station.latitude, station.longitude, width, height,
                    pixelsPerMeter, metersPerDegreeLon);
            if (point[0] < -40f || point[0] > width + 40f
                    || point[1] < -40f || point[1] > height + 40f) continue;
            // Cheapest stations also draw a price above the pump.
            occupied.add(new RectF(point[0] - (station.cheapest ? 34f : 17f),
                    point[1] - (station.cheapest ? 34f : 18f),
                    point[0] + (station.cheapest ? 34f : 17f), point[1] + 18f));
        }
        for (Radar radar : radars) {
            float[] point = roadPointToScreen(radar.latitude, radar.longitude, width, height,
                    pixelsPerMeter, metersPerDegreeLon);
            if (point[0] < -30f || point[0] > width + 30f
                    || point[1] < -30f || point[1] > height + 30f) continue;
            occupied.add(new RectF(point[0] - 18f, point[1] - 24f,
                    point[0] + 18f, point[1] + 15f));
        }
        return occupied;
    }

    private RoadLabelCandidate createRoadLabelCandidate(String text, Road road, int priority,
                                                         float textSize, float textWidth,
                                                         int width, int height,
                                                         float pixelsPerMeter,
                                                         float metersPerDegreeLon) {
        float bestScore = Float.MAX_VALUE;
        float bestX = 0f, bestY = 0f, bestAngle = 0f;
        float visibleLength = 0f;
        for (int i = 0; i + 3 < road.points.length; i += 2) {
            float[] first = roadPointToScreen(road.points[i], road.points[i + 1], width, height,
                    pixelsPerMeter, metersPerDegreeLon);
            float[] second = roadPointToScreen(road.points[i + 2], road.points[i + 3], width,
                    height, pixelsPerMeter, metersPerDegreeLon);
            float dx = second[0] - first[0];
            float dy = second[1] - first[1];
            float segmentLength = (float) Math.hypot(dx, dy);
            if (segmentLength < 2f) continue;
            boolean nearViewport = Math.max(first[0], second[0]) >= -30f
                    && Math.min(first[0], second[0]) <= width + 30f
                    && Math.max(first[1], second[1]) >= -30f
                    && Math.min(first[1], second[1]) <= height + 30f;
            if (nearViewport) visibleLength += Math.min(segmentLength,
                    (float) Math.hypot(width, height));
            float x = (first[0] + second[0]) * .5f;
            float y = (first[1] + second[1]) * .5f;
            float angle = readableRoadAngle((float) Math.toDegrees(Math.atan2(dy, dx)));
            float halfWidth = textWidth * .5f;
            float halfHeight = textSize * .60f;
            double radians = Math.toRadians(angle);
            float boxHalfWidth = (float) (Math.abs(Math.cos(radians)) * halfWidth
                    + Math.abs(Math.sin(radians)) * halfHeight) + 7f;
            float boxHalfHeight = (float) (Math.abs(Math.sin(radians)) * halfWidth
                    + Math.abs(Math.cos(radians)) * halfHeight) + 6f;
            if (x - boxHalfWidth < 24f || x + boxHalfWidth > width - 24f
                    || y - boxHalfHeight < 28f || y + boxHalfHeight > height - 34f) continue;
            // OSM-style decluttering: keep names away from the position marker and prefer a
            // calm placement roughly one road block away instead of piling every junction name
            // under the vehicle.
            float fromCenter = (float) Math.hypot(x - vehicleAnchorX(width),
                    y - vehicleAnchorY(height));
            if (fromCenter < 82f) continue;
            float score = Math.abs(fromCenter - 145f) + Math.abs(angle) * .08f;
            if (score < bestScore) {
                bestScore = score;
                bestX = x;
                bestY = y;
                bestAngle = angle;
            }
        }
        if (bestScore == Float.MAX_VALUE || visibleLength < textWidth * 1.20f) return null;
        double radians = Math.toRadians(bestAngle);
        float halfWidth = textWidth * .5f;
        float halfHeight = textSize * .60f;
        float boxHalfWidth = (float) (Math.abs(Math.cos(radians)) * halfWidth
                + Math.abs(Math.sin(radians)) * halfHeight) + 7f;
        float boxHalfHeight = (float) (Math.abs(Math.sin(radians)) * halfWidth
                + Math.abs(Math.cos(radians)) * halfHeight) + 6f;
        return new RoadLabelCandidate(text, priority, textSize, bestX, bestY, bestAngle,
                bestScore, new RectF(bestX - boxHalfWidth, bestY - boxHalfHeight,
                bestX + boxHalfWidth, bestY + boxHalfHeight));
    }

    private float[] roadPointToScreen(double pointLatitude, double pointLongitude,
                                      int width, int height, float pixelsPerMeter,
                                      float metersPerDegreeLon) {
        float east = (float) ((pointLongitude - renderLongitude) * metersPerDegreeLon);
        float north = (float) ((pointLatitude - renderLatitude) * 111_320d);
        float anchorX = vehicleAnchorX(width);
        float anchorY = vehicleAnchorY(height);
        float x = anchorX + east * pixelsPerMeter;
        float y = anchorY - north * pixelsPerMeter;
        if (!headingReady) return new float[]{x, y};
        double angle = Math.toRadians(-heading);
        float dx = x - anchorX;
        float dy = y - anchorY;
        return new float[]{anchorX + (float) (dx * Math.cos(angle) - dy * Math.sin(angle)),
                anchorY + (float) (dx * Math.sin(angle) + dy * Math.cos(angle))};
    }

    private static float readableRoadAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        if (angle > 90f) angle -= 180f;
        if (angle < -90f) angle += 180f;
        return angle;
    }

    private static boolean intersectsAny(RectF candidate, List<RectF> occupied) {
        for (RectF bounds : occupied) if (RectF.intersects(candidate, bounds)) return true;
        return false;
    }

    private static int roadLabelPriority(Road road) {
        String type = road.roadClass.toLowerCase(Locale.ROOT);
        if ("motorway".equals(type)) return 0;
        if ("trunk".equals(type)) return 1;
        if ("primary".equals(type)) return 2;
        if ("secondary".equals(type)) return 3;
        if ("tertiary".equals(type)) return 4;
        if ("residential".equals(type) || "unclassified".equals(type)) return 5;
        return 6;
    }

    private static float roadLabelTextSize(int width, int priority) {
        float base = Math.max(9.5f, Math.min(13f, width / 48f));
        if (priority <= 1) return base + 1f;
        if (priority >= 5) return base - 1f;
        return base;
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
        final float pixelsPerMeter = mapPixelsPerMeter(width, height);
        final float metersPerDegreeLon = (float) (111_320d * Math.cos(Math.toRadians(renderLatitude)));
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        labelPaint.setTextSize(Math.max(8f, Math.min(11f, width / 55f)));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        int drawn = 0;
        for (Radar radar : radars) {
            float[] screen = roadPointToScreen(radar.latitude, radar.longitude, width, height,
                    pixelsPerMeter, metersPerDegreeLon);
            float x = screen[0];
            float y = screen[1];
            if (x < 18f || x > width - 18f || y < 22f || y > height - 22f) continue;
            drawRadarCones(canvas, radar, x, y, pixelsPerMeter);
            // Draw the installation over the cone so the point remains exact and legible.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(95, 241, 45, 45));
            canvas.drawCircle(x, y, 11f, paint);
            paint.setColor(Color.rgb(224, 42, 42));
            canvas.drawCircle(x, y, 6.5f, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, 2.2f, paint);
            labelPaint.setColor(Color.rgb(255, 173, 104));
            canvas.drawText(radar.limitKmh == null ? "R" : String.valueOf(radar.limitKmh),
                    x, y - 10f, labelPaint);
            if (++drawn >= 24) break;
        }
    }

    private float mapPixelsPerMeter(int width, int height) {
        float base = Math.min(width, height) / 420f * zoomScale;
        if (radarAlertId.isEmpty() || radars.isEmpty()) return base;
        Radar focus = null;
        for (Radar radar : radars) {
            if (radarAlertId.equals(radar.id)) { focus = radar; break; }
        }
        if (focus == null) return base;
        float metersPerDegreeLon = (float) (111_320d
                * Math.cos(Math.toRadians(renderLatitude)));
        float[] point = roadPointToScreen(focus.latitude, focus.longitude, width, height,
                base, metersPerDegreeLon);
        float anchorX = vehicleAnchorX(width), anchorY = vehicleAnchorY(height);
        float dx = Math.abs(point[0] - anchorX), dy = Math.abs(point[1] - anchorY);
        float extensionX = 36f * base; // cone width, in metres, at the radar
        float extensionY = 100f * base; // detection corridor continues 100 m beyond it
        float availableX = Math.max(12f, width * .5f - 24f - extensionX);
        float availableY = Math.max(12f, (point[1] < anchorY
                ? anchorY - 24f : height - anchorY - 24f) - extensionY);
        float factor = 1f;
        if (dx > availableX) factor = Math.min(factor, availableX / dx);
        if (dy > availableY) factor = Math.min(factor, availableY / dy);
        // Preserve a useful street view while making the alert point and its downstream cone fit.
        return base * Math.max(.28f, Math.min(1f, factor * .96f));
    }

    private void drawRadarCones(Canvas canvas, Radar radar, float x, float y,
                                float pixelsPerMeter) {
        float[] bearings = radar.directionBearings;
        if (radar.bidirectional && bearings.length == 0 && headingReady) {
            bearings = new float[]{heading, normalize(heading + 180f)};
        } else if (radar.bidirectional && bearings.length == 1) {
            bearings = new float[]{bearings[0], normalize(bearings[0] + 180f)};
        }
        for (float bearing : bearings) {
            if (!Float.isFinite(bearing)) continue;
            float visualBearing = bearing;
            // A single-direction cone is drawn only when the current course agrees within the
            // tolerance. Near 180 degrees is the opposite carriageway; the middle sector is
            // uncertain and deliberately gets a warning/marker without a directional cone.
            if (!radar.bidirectional && moving) {
                if (!headingReady || angularDifference(heading, bearing) > 55f) continue;
                // The source heading selects the right carriageway; the current map-matched
                // course gives the cone the road's exact local tangent through bends and small
                // metadata bearing errors. This keeps the cone on the active segment.
                visualBearing = heading;
            } else if (radar.bidirectional && moving && headingReady) {
                visualBearing = angularDifference(heading, bearing) <= 90f
                        ? heading : normalize(heading + 180f);
            }
            drawRadarCone(canvas, x, y, pixelsPerMeter, visualBearing);
        }
    }

    private void drawRadarCone(Canvas canvas, float x, float y, float pixelsPerMeter,
                               float directionBearing) {
        // Apex sits on the detector; the broad end points downrange, where its beam covers.
        canvas.save();
        canvas.rotate(normalize(directionBearing - (headingReady ? heading : 0f)), x, y);
        float coneLength = Math.max(30f, Math.min(110f, pixelsPerMeter * 100f));
        float coneHalfWidth = Math.max(12f, Math.min(34f, coneLength * .32f));
        Path cone = new Path();
        cone.moveTo(x, y - 4f);
        cone.lineTo(x - coneHalfWidth, y - coneLength);
        cone.quadTo(x, y - coneLength - 8f, x + coneHalfWidth, y - coneLength);
        cone.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(92, 255, 42, 35));
        canvas.drawPath(cone, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(Color.argb(205, 255, 111, 91));
        canvas.drawPath(cone, paint);
        paint.setStyle(Paint.Style.FILL);
        Path tip = new Path();
        tip.moveTo(x, y - coneLength - 8f);
        tip.lineTo(x - 4f, y - coneLength);
        tip.lineTo(x + 4f, y - coneLength);
        tip.close();
        paint.setColor(Color.rgb(255, 214, 199));
        canvas.drawPath(tip, paint);
        canvas.restore();
    }

    private void drawFuelStations(Canvas canvas, int width, int height) {
        if (fuelStations.isEmpty()) return;
        final float pixelsPerMeter = mapPixelsPerMeter(width, height);
        final float metersPerDegreeLon = (float) (111_320d * Math.cos(Math.toRadians(renderLatitude)));
        labelPaint.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.BOLD));
        labelPaint.setTextSize(Math.max(8f, Math.min(10f, width / 58f)));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        int drawn = 0;
        float anchorX = vehicleAnchorX(width);
        float anchorY = vehicleAnchorY(height);
        for (FuelStation station : fuelStations) {
            float east = (float) ((station.longitude - renderLongitude) * metersPerDegreeLon);
            float north = (float) ((station.latitude - renderLatitude) * 111_320d);
            float x = anchorX + east * pixelsPerMeter;
            float y = anchorY - north * pixelsPerMeter;
            if (headingReady) {
                double angle = Math.toRadians(-heading);
                float dx = x - anchorX;
                float dy = y - anchorY;
                x = anchorX + (float) (dx * Math.cos(angle) - dy * Math.sin(angle));
                y = anchorY + (float) (dx * Math.sin(angle) + dy * Math.cos(angle));
            }
            if (x < 18f || x > width - 18f || y < 22f || y > height - 22f) continue;
            // Same price semantics as the stationary fuel card: cheapest is green; the other
            // visible stations are red. Proximity alone never implies a good price.
            int color = station.cheapest ? Color.rgb(59, 205, 118)
                    : Color.rgb(224, 68, 62);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(station.cheapest ? 105 : 65,
                    Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(x, y, station.cheapest ? 13f : 10f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(station.cheapest ? 2.2f : 1.7f);
            paint.setColor(color);
            RectF body = new RectF(x - 5.5f, y - 7f, x + 3.5f, y + 6.5f);
            canvas.drawRoundRect(body, 1.5f, 1.5f, paint);
            canvas.drawLine(x - 3.5f, y - 3.5f, x + 1.5f, y - 3.5f, paint);
            Path hose = new Path();
            hose.moveTo(x + 3.5f, y - 4f);
            hose.cubicTo(x + 8f, y - 3f, x + 7f, y + 4f, x + 8f, y + 5f);
            canvas.drawPath(hose, paint);
            paint.setStyle(Paint.Style.FILL);
            if (station.cheapest && station.price > 0d) {
                labelPaint.setColor(Color.rgb(210, 255, 226));
                labelPaint.setShadowLayer(2f, 0f, 1f, Color.BLACK);
                canvas.drawText(String.format(Locale.getDefault(), "%.3f", station.price),
                        x, y - 14f, labelPaint);
                labelPaint.clearShadowLayer();
            }
            if (++drawn >= 18) break;
        }
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

        float cx = vehicleAnchorX(width);
        float cy = vehicleAnchorY(height);
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
        float cx = vehicleAnchorX(width);
        float cy = vehicleAnchorY(height);
        float markerHeight = Math.max(56f, Math.min(82f, height * .18f));
        float markerWidth = markerHeight * vehicleMarker.getWidth() / vehicleMarker.getHeight();
        RectF destination = new RectF(
                cx - markerWidth / 2f,
                cy - markerHeight / 2f,
                cx + markerWidth / 2f,
                cy + markerHeight / 2f);

        if (vehicleAlpha != null) {
            // Do not rely on BlurMaskFilter: the JCRK01/CYA GPU drops that effect. Expanded
            // alpha silhouettes create a small white separation halo with regular bitmap draws.
            vehicleHaloPaint.setColor(Color.WHITE);
            for (int layer = 6; layer >= 1; layer--) {
                RectF halo = new RectF(destination);
                float expansion = .72f * layer;
                halo.inset(-expansion, -expansion);
                vehicleHaloPaint.setAlpha(18 + (6 - layer) * 13);
                canvas.drawBitmap(vehicleAlpha, null, halo, vehicleHaloPaint);
            }
            vehicleHaloPaint.setAlpha(255);
        }
        canvas.drawBitmap(vehicleMarker, null, destination, vehiclePaint);
    }

    private static float vehicleAnchorX(int width) { return width * .5f; }

    private static float vehicleAnchorY(int height) { return height * VEHICLE_ANCHOR_Y_RATIO; }

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
