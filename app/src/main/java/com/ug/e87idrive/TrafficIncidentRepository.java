package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live DGT DATEX II incidents. No stale event is presented as current. */
final class TrafficIncidentRepository {
    interface Listener { void onChanged(); }
    static final int AMBER = 1;
    static final int RED = 2;
    private static final String URL = "https://nap.dgt.es/datex2/v3/dgt/"
            + "SituationPublication/datex2_v37.xml";
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final long REFRESH_MS = 2L * 60L * 1000L;
    private static final long LIVE_GRACE_MS = 10L * 60L * 1000L;
    private static final long TOMTOM_REFRESH_MS = 10L * 60L * 1000L;
    private static final String TOMTOM_PREFERENCES = "tomtom_traffic";
    private static final String TOMTOM_KEY = "api_key";
    private static final String TOMTOM_ENABLED = "enabled";
    private final Context context;
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final SharedPreferences tomTomPreferences;
    private volatile List<Incident> incidents = Collections.emptyList();
    private volatile List<Incident> dgtIncidents = Collections.emptyList();
    private volatile List<Incident> tomTomIncidents = Collections.emptyList();
    private volatile boolean active, loading;
    private volatile long lastSuccess, lastAttempt, lastTomTomSuccess;
    private Location location;
    private boolean callbackRegistered;

    static final class Incident {
        final String id, title, road, detail, source;
        final int severity;
        final double[] points;
        final long validUntil;
        final int delaySeconds;
        final double lengthMeters;
        final int magnitude;
        Incident(String id, String title, String road, String detail, String source,
                 int severity, double[] points, long validUntil) {
            this(id, title, road, detail, source, severity, points, validUntil, 0, 0d, 0);
        }
        Incident(String id, String title, String road, String detail, String source,
                 int severity, double[] points, long validUntil, int delaySeconds,
                 double lengthMeters, int magnitude) {
            this.id=id; this.title=title; this.road=road; this.detail=detail;
            this.source=source; this.severity=severity; this.points=points;
            this.validUntil=validUntil;
            this.delaySeconds=Math.max(0,delaySeconds);this.lengthMeters=Math.max(0d,lengthMeters);
            this.magnitude=Math.max(0,magnitude);
        }
    }

    static final class Alert {
        final Incident incident;
        final double distanceMeters;
        Alert(Incident incident, double distanceMeters) {
            this.incident=incident; this.distanceMeters=distanceMeters;
        }
    }

    static final class Classification {
        final int severity; final String title;
        Classification(int severity,String title){this.severity=severity;this.title=title;}
    }

    static Classification classify(String raw) {
        String value=raw==null?"":raw.toLowerCase(Locale.ROOT);
        if(containsToken(value,"vehicleonfire"))
            return new Classification(RED,"INCIDENCIA EN LA VÍA");
        if(containsToken(value,"stationaryvehicle","vehicleobstruction","vehiclestuck",
                "brokendown","vehicleonroad"))
            return new Classification(RED,"VEHÍCULO PARADO");
        if(containsToken(value,"slowtraffic","heavytraffic","queuingtraffic","congestion",
                "trafficbuildingup"))
            return new Classification(AMBER,"CIRCULACIÓN LENTA");
        if(containsToken(value,"accident","roadclosed","obstruction","blocked"))
            return new Classification(RED,"INCIDENCIA EN LA VÍA");
        return null;
    }

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { refreshIfNeeded(true); }
                @Override public void onCapabilitiesChanged(Network network,
                        NetworkCapabilities capabilities) { refreshIfNeeded(false); }
            };

    TrafficIncidentRepository(Context context, Listener listener) {
        this.context=context.getApplicationContext(); this.listener=listener;
        connectivity=(ConnectivityManager)this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        tomTomPreferences=this.context.getSharedPreferences(TOMTOM_PREFERENCES,
                Context.MODE_PRIVATE);
        lastTomTomSuccess=tomTomPreferences.getLong("last_success",0L);
    }

    boolean tomTomConfigured(){return !tomTomPreferences.getString(TOMTOM_KEY,"").trim().isEmpty();}
    boolean tomTomEnabled(){return tomTomConfigured()&&tomTomPreferences.getBoolean(TOMTOM_ENABLED,false);}
    String tomTomStatus(){
        if(!tomTomConfigured())return "TomTom: sin configurar";
        if(!tomTomEnabled())return "TomTom: configurado · desactivado";
        return "TomTom conectado: activo · cuota protegida";
    }
    void configureTomTom(String key,boolean enabled){
        String clean=key==null?"":key.trim();
        SharedPreferences.Editor edit=tomTomPreferences.edit();
        if(clean.isEmpty())edit.remove(TOMTOM_KEY).putBoolean(TOMTOM_ENABLED,false);
        else edit.putString(TOMTOM_KEY,clean).putBoolean(TOMTOM_ENABLED,enabled);
        edit.apply();
        tomTomIncidents=Collections.emptyList(); combineIncidents(); lastTomTomSuccess=0L;
        if(active)refreshIfNeeded(true);
    }

    synchronized void start(Location current) {
        active=true;
        if(current!=null) location=new Location(current);
        register();
        refreshIfNeeded(false);
    }

    synchronized void stop() { active=false; unregister(); }

    synchronized void close() { stop(); executor.shutdownNow(); }

    synchronized void onLocation(Location value) {
        if(value==null)return;
        location=new Location(value);
        refreshIfNeeded(false);
    }

    Alert alert(Location current) {
        if(current==null)return null;
        long now=System.currentTimeMillis();
        Incident selected=null; double best=Double.MAX_VALUE; double bestScore=Double.MAX_VALUE;
        for(Incident incident:incidents) {
            if(incident.validUntil<now)continue;
            double distance=nearestDistance(current,incident.points);
            if(distance>5_000d)continue;
            boolean close=distance<=650d;
            boolean ahead=true;
            if(!close && current.hasBearing()) {
                Location target=nearestPoint(current,incident.points);
                ahead=target!=null && DrivingFuelStationPolicy.angularDifference(
                        current.getBearing(),current.bearingTo(target))<=60f;
            }
            if(ahead){
                // Prefer closures/severe delays before a slightly nearer minor slowdown.
                double score=distance-(incident.severity==RED?700d:0d)
                        -Math.min(900d,incident.delaySeconds*.35d)-incident.magnitude*120d;
                if(score<bestScore){bestScore=score;best=distance;selected=incident;}
            }
        }
        return selected==null?null:new Alert(selected,best);
    }

    List<OfflineRoadMapView.Incident> mapIncidents(Location current) {
        if(current==null)return Collections.emptyList();
        long now=System.currentTimeMillis();
        ArrayList<OfflineRoadMapView.Incident> result=new ArrayList<>();
        for(Incident incident:incidents) if(incident.validUntil>=now
                && nearestDistance(current,incident.points)<=10_000d) {
            result.add(new OfflineRoadMapView.Incident(incident.id,incident.severity,
                    incident.points));
        }
        return result;
    }

    private synchronized void refreshIfNeeded(boolean forceAfterNetwork) {
        if(!active||loading||location==null||!hasInternet())return;
        long now=System.currentTimeMillis();
        if(now-lastAttempt<60_000L)return;
        if(!forceAfterNetwork&&now-lastSuccess<REFRESH_MS)return;
        loading=true;lastAttempt=now;
        Location requestedLocation=new Location(location);
        executor.execute(()->download(now,requestedLocation));
    }

    private void download(long requestedAt,Location requestedLocation) {
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(URL).openConnection();
            connection.setConnectTimeout(15_000);connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent","BMW-E87-iDrive/1.28 (DGT incidents)");
            if(connection.getResponseCode()!=200)throw new IOException("HTTP "+connection.getResponseCode());
            ArrayList<Incident> parsed;
            try(InputStream input=new LimitedInputStream(connection.getInputStream(),MAX_BYTES)){
                parsed=parse(input,requestedAt);
            }
            dgtIncidents=Collections.unmodifiableList(parsed);
            lastSuccess=System.currentTimeMillis();
            AppSessionLog.event("INCIDENCIAS DGT",parsed.size()+" incidencias activas útiles");
        }catch(Exception error){
            AppSessionLog.sampledEvent("traffic-incidents-download","INCIDENCIAS DGT",
                    "Actualización pendiente · "+error.getClass().getSimpleName(),60_000L);
        }finally{
            if(connection!=null)connection.disconnect();
        }
        if(tomTomEnabled()&&requestedAt-lastTomTomSuccess>=TOMTOM_REFRESH_MS
                &&TomTomUsageBudget.reserve(tomTomPreferences,TomTomUsageBudget.INCIDENTS,
                TomTomUsageBudget.INCIDENT_LIMIT,requestedAt))downloadTomTom(requestedLocation,requestedAt);
        combineIncidents();loading=false;
        main.post(()->{if(listener!=null)listener.onChanged();});
    }

    private void downloadTomTom(Location center,long requestedAt){
        HttpURLConnection connection=null;
        try{
            String key=tomTomPreferences.getString(TOMTOM_KEY,"").trim();
            if(key.isEmpty())return;
            double lat=center.getLatitude(),lon=center.getLongitude();
            String bbox=String.format(Locale.ROOT,"%.6f,%.6f,%.6f,%.6f",
                    lon-.075d,lat-.055d,lon+.075d,lat+.055d);
            Uri uri=Uri.parse("https://api.tomtom.com/traffic/services/5/incidentDetails")
                    .buildUpon().appendQueryParameter("key",key).appendQueryParameter("bbox",bbox)
                    .appendQueryParameter("fields","{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,delay,length,probabilityOfOccurrence,numberOfReports,lastReportTime,events{description},from,to,roadNumbers}}}")
                    .appendQueryParameter("language","es-ES")
                    .appendQueryParameter("timeValidityFilter","present").build();
            connection=(HttpURLConnection)new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(15_000);connection.setReadTimeout(45_000);
            connection.setRequestProperty("User-Agent","BMW-E87-iDrive/1.28 (TomTom Traffic)");
            int status=connection.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);
            String json=readText(connection.getInputStream(),2*1024*1024);
            ArrayList<Incident> parsed=parseTomTom(json,requestedAt);
            tomTomIncidents=Collections.unmodifiableList(parsed);
            lastTomTomSuccess=System.currentTimeMillis();
            tomTomPreferences.edit().putLong("last_success",lastTomTomSuccess).apply();
            AppSessionLog.event("TRÁFICO TOMTOM",parsed.size()+" incidencias útiles");
        }catch(Exception error){
            AppSessionLog.sampledEvent("tomtom-traffic-download","TRÁFICO TOMTOM",
                    "Actualización pendiente · "+error.getClass().getSimpleName(),10L*60L*1000L);
        }finally{if(connection!=null)connection.disconnect();}
    }

    private synchronized void combineIncidents(){
        ArrayList<Incident> combined=new ArrayList<>(dgtIncidents.size()+tomTomIncidents.size());
        combined.addAll(dgtIncidents);
        for(Incident candidate:tomTomIncidents){
            boolean duplicate=false;
            for(Incident official:dgtIncidents){
                if(candidate.severity==official.severity
                        &&incidentDistance(candidate,official)<=180d){duplicate=true;break;}
            }
            if(!duplicate)combined.add(candidate);
        }
        incidents=Collections.unmodifiableList(combined);
    }

    private static double incidentDistance(Incident first,Incident second){
        if(first.points.length<2||second.points.length<2)return Double.MAX_VALUE;
        Location point=new Location("incident-a");point.setLatitude(first.points[0]);point.setLongitude(first.points[1]);
        return nearestDistance(point,second.points);
    }

    static ArrayList<Incident> parseTomTom(String json,long fetchedAt)throws Exception{
        ArrayList<Incident> result=new ArrayList<>();
        JSONArray values=new JSONObject(json).optJSONArray("incidents");
        for(int i=0;values!=null&&i<values.length();i++){
            JSONObject value=values.optJSONObject(i);if(value==null)continue;
            JSONObject properties=value.optJSONObject("properties");
            JSONObject geometry=value.optJSONObject("geometry");
            if(properties==null||geometry==null)continue;
            Classification classification=classifyTomTom(properties.opt("iconCategory"));
            if(classification==null)continue;
            ArrayList<Double> coordinates=new ArrayList<>();
            collectGeoJson(geometry.optJSONArray("coordinates"),coordinates);
            if(coordinates.size()<2)continue;
            double[] points=new double[coordinates.size()];
            for(int p=0;p<points.length;p++)points[p]=coordinates.get(p);
            String road=join(properties.optJSONArray("roadNumbers"));
            if(road.isEmpty())road=first(properties.optString("from"),properties.optString("to"),"VÍA SIN REFERENCIA");
            String detail="";JSONArray events=properties.optJSONArray("events");
            if(events!=null&&events.length()>0){JSONObject event=events.optJSONObject(0);if(event!=null)detail=event.optString("description","");}
            int delay=properties.optInt("delay",0);
            double length=properties.optDouble("length",0d);
            int magnitude=properties.optInt("magnitudeOfDelay",0);
            if(delay>=60){String delayLabel=String.format(Locale.ROOT,"retraso %d min",Math.max(1,Math.round(delay/60f)));
                detail=detail.isEmpty()?delayLabel:detail+" · "+delayLabel;}
            String id=properties.optString("id","tomtom-"+i);
            result.add(new Incident(id,classification.title,road,detail,"TOMTOM",
                    classification.severity,points,fetchedAt+15L*60L*1000L,delay,length,magnitude));
        }
        return result;
    }

    private static Classification classifyTomTom(Object raw){
        String value=raw==null?"":String.valueOf(raw).toLowerCase(Locale.ROOT);
        if(value.equals("1")||value.contains("accident")||value.equals("7")
                ||value.contains("laneclosed")||value.equals("8")||value.contains("roadclosed"))
            return new Classification(RED,"INCIDENCIA EN LA VÍA");
        if(value.equals("14")||value.contains("brokendown"))
            return new Classification(RED,"VEHÍCULO PARADO");
        if(value.equals("6")||value.contains("jam")||value.contains("traffic"))
            return new Classification(AMBER,"CIRCULACIÓN LENTA");
        if(value.equals("9")||value.contains("roadwork"))
            return new Classification(AMBER,"OBRAS EN LA VÍA");
        return null;
    }

    private static void collectGeoJson(JSONArray values,ArrayList<Double> out){
        if(values==null||values.length()==0)return;
        if(values.opt(0) instanceof Number&&values.length()>=2){
            double lon=values.optDouble(0,Double.NaN),lat=values.optDouble(1,Double.NaN);
            if(Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90d&&Math.abs(lon)<=180d){out.add(lat);out.add(lon);}return;
        }
        for(int i=0;i<values.length();i++)collectGeoJson(values.optJSONArray(i),out);
    }
    private static String join(JSONArray values){StringBuilder out=new StringBuilder();for(int i=0;values!=null&&i<values.length();i++){String value=values.optString(i,"").trim();if(!value.isEmpty()){if(out.length()>0)out.append(" · ");out.append(value);}}return out.toString();}
    private static String first(String...values){for(String value:values)if(value!=null&&!value.trim().isEmpty())return value.trim();return "";}
    private static String readText(InputStream input,int maximum)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new LimitedInputStream(input,maximum)){byte[] bytes=new byte[8192];int count;while((count=in.read(bytes))!=-1)out.write(bytes,0,count);}return out.toString("UTF-8");}

    static ArrayList<Incident> parse(InputStream input,long fetchedAt)throws Exception{
        ArrayList<Incident> result=new ArrayList<>();
        XmlPullParser parser=Xml.newPullParser();
        parser.setInput(new BufferedInputStream(input),"UTF-8");
        Builder builder=null;String field=null;
        while(parser.next()!=XmlPullParser.END_DOCUMENT){
            int event=parser.getEventType();String name=local(parser.getName());
            if(event==XmlPullParser.START_TAG){
                if("situationRecord".equals(name)){
                    builder=new Builder(parser.getAttributeValue(null,"id"),
                            parser.getAttributeValue("http://www.w3.org/2001/XMLSchema-instance","type"));
                }
                field=name;
            }else if(event==XmlPullParser.TEXT&&builder!=null&&field!=null){
                String value=parser.getText()==null?"":parser.getText().trim();
                if(!value.isEmpty())builder.accept(field,value);
            }else if(event==XmlPullParser.END_TAG){
                if("situationRecord".equals(name)&&builder!=null){
                    Incident incident=builder.build(fetchedAt);
                    if(incident!=null)result.add(incident);
                    builder=null;
                }
                field=null;
            }
        }
        return result;
    }

    private static final class Builder{
        final String id;final StringBuilder classification=new StringBuilder();
        final ArrayList<Double> coordinates=new ArrayList<>();
        String road="",municipality="",validity="";Double pendingLat;
        Builder(String id,String type){this.id=id==null?"":id;if(type!=null)classification.append(type);}
        void accept(String field,String value){
            if("roadName".equals(field)&&road.isEmpty())road=value;
            else if("municipality".equals(field)&&municipality.isEmpty())municipality=value;
            else if("validityStatus".equals(field))validity=value;
            else if("latitude".equals(field)){try{pendingLat=Double.parseDouble(value);}catch(Exception ignored){}}
            else if("longitude".equals(field)&&pendingLat!=null){try{coordinates.add(pendingLat);coordinates.add(Double.parseDouble(value));}catch(Exception ignored){}pendingLat=null;}
            if(field.endsWith("Type")||"trafficStatus".equals(field)||"causeType".equals(field))
                classification.append(' ').append(value);
        }
        Incident build(long fetchedAt){
            if(!"active".equalsIgnoreCase(validity)||coordinates.size()<2)return null;
            Classification type=classify(classification.toString());
            if(type==null)return null;
            double[] points=new double[coordinates.size()];for(int i=0;i<points.length;i++)points[i]=coordinates.get(i);
            return new Incident(id,type.title,road.isEmpty()?"VÍA SIN REFERENCIA":road,
                    municipality,"DGT",type.severity,points,fetchedAt+LIVE_GRACE_MS);
        }
    }

    private static boolean containsToken(String value,String...tokens){for(String token:tokens)if(value.contains(token))return true;return false;}

    private static double nearestDistance(Location current,double[] points){
        Location nearest=nearestPoint(current,points);return nearest==null?Double.MAX_VALUE:current.distanceTo(nearest);
    }
    private static Location nearestPoint(Location current,double[] points){
        Location best=null;float distance=Float.MAX_VALUE;
        for(int i=0;i+1<points.length;i+=2){Location point=new Location("incident");point.setLatitude(points[i]);point.setLongitude(points[i+1]);float d=current.distanceTo(point);if(d<distance){distance=d;best=point;}}
        return best;
    }
    private boolean hasInternet(){
        if(connectivity==null)return false;Network network=connectivity.getActiveNetwork();
        NetworkCapabilities capabilities=network==null?null:connectivity.getNetworkCapabilities(network);
        return capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private void register(){if(callbackRegistered||connectivity==null)return;try{connectivity.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback);callbackRegistered=true;}catch(Exception ignored){}}
    private void unregister(){if(!callbackRegistered||connectivity==null)return;try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}callbackRegistered=false;}
    private static String local(String name){if(name==null)return"";int colon=name.indexOf(':');return colon<0?name:name.substring(colon+1);}
    private static final class LimitedInputStream extends FilterInputStream{
        int remaining;LimitedInputStream(InputStream input,int maximum){super(input);remaining=maximum;}
        @Override public int read()throws IOException{if(remaining<=0)throw new IOException("Feed demasiado grande");int value=super.read();if(value>=0)remaining--;return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)throw new IOException("Feed demasiado grande");int count=super.read(b,off,Math.min(len,remaining));if(count>0)remaining-=count;return count;}
    }
}
