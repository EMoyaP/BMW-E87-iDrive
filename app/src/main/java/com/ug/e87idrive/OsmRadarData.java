package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** ODbL camera layer, kept separate from official DGT records. No network during lookup. */
final class OsmRadarData {
    static final long DAY = 86_400_000L;
    static final long RETRY = 1_800_000L;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final class Camera {
        final String id, road, direction, point, metadata;
        Camera(String id, String road, String direction, String point, String metadata) {
            this.id=id; this.road=road; this.direction=direction; this.point=point; this.metadata=metadata;
        }
    }

    static String query(long relation) {
        return "[out:json][timeout:60];area(" + (3600000000L+relation) + ")->.p;"
                + "node(area.p)[highway=speed_camera]->.c;"
                + "(rel(bn.c)[type=enforcement];rel(area.p)[type=enforcement][enforcement=maxspeed];)->.r;"
                + "(.c;.r;node(r.r););out body;";
    }

    /** Called on the existing OSM update worker; one request, no endpoint hopping/retry storm. */
    static synchronized String refresh(Context context, Network network, String province, long relation)
            throws Exception {
        SharedPreferences p=context.getSharedPreferences("osm_radar_updates", Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if (now-p.getLong("success_"+province,0)<DAY) return "radares OSM vigentes (<24 h)";
        if (now-p.getLong("attempt_"+province,0)<RETRY) throw new IOException("Radares OSM: espera 30 min antes de reintentar");
        p.edit().putLong("attempt_"+province,now).apply();
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)network.openConnection(new URL("https://overpass-api.de/api/interpreter"));
            c.setConnectTimeout(15000); c.setReadTimeout(80000);
            c.setRequestProperty("User-Agent","BMW-E87-iDrive/1.26 (offline provincial cameras)");
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (java.io.OutputStream output=c.getOutputStream()) {
                output.write(("data="+URLEncoder.encode(query(relation),"UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode()!=200) throw new IOException("Radares OSM HTTP "+c.getResponseCode());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()) {
                byte[] b=new byte[8192]; int n;
                while((n=in.read(b))!=-1) {
                    if(out.size()+n>MAX_BYTES) throw new IOException("Radares OSM: respuesta demasiado grande");
                    out.write(b,0,n);
                }
            }
            ArrayList<Camera> cameras=parse(new JSONObject(out.toString(StandardCharsets.UTF_8.name())));
            RadarRepository.replaceOsm(context, province, cameras, now);
            p.edit().putLong("success_"+province,now).putInt("count_"+province,cameras.size()).apply();
            AppSessionLog.event("RADARES OSM",province+" · "+cameras.size()+" fijos · descarga e instalación correctas");
            return cameras.size()+" radares OSM · "+province;
        } finally { if(c!=null)c.disconnect(); }
    }

    static ArrayList<Camera> parse(JSONObject root) throws Exception {
        if(root.has("remark")) throw new IOException("Overpass incompleto: "+root.optString("remark"));
        JSONArray elements=root.getJSONArray("elements");
        Map<Long,JSONObject> nodes=new HashMap<>();
        Map<Long,JSONArray> corridors=new HashMap<>();
        Set<Long> excluded=new HashSet<>();
        for(int i=0;i<elements.length();i++) {
            JSONObject e=elements.getJSONObject(i);
            if("node".equals(e.optString("type"))) nodes.put(e.getLong("id"),e);
        }
        for(int i=0;i<elements.length();i++) {
            JSONObject e=elements.getJSONObject(i), tags=e.optJSONObject("tags");
            if(!"relation".equals(e.optString("type")) || tags==null) continue;
            JSONArray members=e.optJSONArray("members");
            if(members==null)continue;
            ArrayList<Long> devices=new ArrayList<>(), from=new ArrayList<>(), to=new ArrayList<>();
            boolean force=false;
            for(int j=0;j<members.length();j++) {
                JSONObject m=members.getJSONObject(j);
                if("force".equals(m.optString("role")))force=true;
                if(!"node".equals(m.optString("type")))continue;
                String role=m.optString("role"); long id=m.getLong("ref");
                if("device".equals(role))devices.add(id);
                if("from".equals(role))from.add(id);
                if("to".equals(role))to.add(id);
            }
            if(!"maxspeed".equals(tags.optString("enforcement")) || force) {
                excluded.addAll(devices); continue;
            }
            // Explicit from/to only: never interpret the optical direction as traffic heading.
            for(long id:devices) for(long f:from) for(long t:to) {
                JSONObject a=nodes.get(f), b=nodes.get(t);
                if(a==null || b==null)continue;
                JSONObject corridor=new JSONObject();
                corridor.put("fromLat",a.getDouble("lat")).put("fromLon",a.getDouble("lon"))
                        .put("toLat",b.getDouble("lat")).put("toLon",b.getDouble("lon"));
                JSONObject deviceTags=nodes.containsKey(id)?nodes.get(id).optJSONObject("tags"):null;
                boolean conditional=tags.has("maxspeed:conditional")
                        || (deviceTags!=null && deviceTags.has("maxspeed:conditional"));
                int relationLimit=parseLimit(tags.optString("maxspeed"));
                int nodeLimit=deviceTags==null?0:parseLimit(deviceTags.optString("maxspeed"));
                int limit=conditional || (relationLimit>0 && nodeLimit>0 && relationLimit!=nodeLimit)
                        ? 0 : relationLimit>0?relationLimit:nodeLimit;
                corridor.put("limit",limit).put("relation",e.getLong("id"));
                if(!corridors.containsKey(id))corridors.put(id,new JSONArray());
                corridors.get(id).put(corridor);
            }
        }
        ArrayList<Camera> result=new ArrayList<>();
        for(Map.Entry<Long,JSONObject> entry:nodes.entrySet()) {
            JSONObject e=entry.getValue(), tags=e.optJSONObject("tags");
            if(tags==null || !"speed_camera".equals(tags.optString("highway")) || excluded.contains(entry.getKey()))continue;
            if("yes".equals(tags.optString("disused")) || "yes".equals(tags.optString("abandoned"))
                    || "no".equals(tags.optString("operational")) || "mobile".equals(tags.optString("camera:type")))continue;
            double lat=e.getDouble("lat"),lon=e.getDouble("lon");
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new IOException("Coordenadas OSM inválidas");
            JSONObject meta=new JSONObject().put("tags",tags);
            meta.put("corridors",corridors.containsKey(entry.getKey())?corridors.get(entry.getKey()):new JSONArray());
            result.add(new Camera("OSM-N"+entry.getKey(),tags.optString("ref"),tags.optString("direction"),lat+","+lon,meta.toString()));
        }
        return result;
    }

    static int parseLimit(String value) {
        if(value==null || !value.trim().matches("[0-9]{1,3}( km/h)?"))return 0;
        int n=Integer.parseInt(value.trim().replace(" km/h",""));
        return n>=10 && n<=130?n:0;
    }
}
