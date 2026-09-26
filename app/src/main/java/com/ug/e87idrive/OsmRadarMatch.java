package com.ug.e87idrive;

import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conservative straight-corridor check. Missing/ambiguous geometry is not a confirmed radar. */
final class OsmRadarMatch {
    static int limit(Location fix, String metadata) {
        if(!fix.hasBearing() || !fix.hasSpeed() || fix.getSpeed()<2.5f || !fix.hasAccuracy()
                || fix.getAccuracy()>20f || (android.os.SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos())>10_000_000_000L)return -1;
        try {
            JSONObject data=new JSONObject(metadata);
            JSONArray corridors=data.getJSONArray("corridors");
            int result=-1;
            for(int i=0;i<corridors.length();i++) {
                JSONObject c=corridors.getJSONObject(i);
                double lat=c.getDouble("fromLat"), lon=c.getDouble("fromLon");
                double scale=111320*Math.cos(Math.toRadians(lat));
                double dx=(c.getDouble("toLon")-lon)*scale, dy=(c.getDouble("toLat")-lat)*111320;
                double x=(fix.getLongitude()-lon)*scale, y=(fix.getLatitude()-lat)*111320;
                if(!matches(x,y,dx,dy,fix.getBearing(),fix.getAccuracy()))continue;
                int n=c.optInt("limit",0);
                if(result>=0 && result!=n)return -1; // conflicting corridors: no guessed limit
                result=n;
            }
            return result;
        }catch(Exception ignored){return -1;}
    }

    static boolean matches(double x,double y,double dx,double dy,double bearing,double accuracy) {
        double length=Math.hypot(dx,dy);
        if(!Double.isFinite(length)||length<15||length>1500||accuracy>20)return false;
        double along=(x*dx+y*dy)/length, lateral=Math.abs(x*dy-y*dx)/length;
        double heading=(Math.toDegrees(Math.atan2(dx,dy))+360)%360;
        double difference=Math.abs((bearing-heading+540)%360-180);
        // No extrapolation before the from-node: a curved/parallel approach needs richer geometry.
        return along>=-10 && along<=length+100 && lateral<=15 && difference<=25;
    }
}
