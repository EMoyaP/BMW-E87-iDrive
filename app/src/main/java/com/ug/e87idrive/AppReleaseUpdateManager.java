package com.ug.e87idrive;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Downloads only a newer, GitHub-digested iDrive APK. Android still asks the user to install it. */
final class AppReleaseUpdateManager {
    interface Progress { void update(int percent,String message); }
    static final class Result {
        final boolean success,ready; final String message;
        Result(boolean success,boolean ready,String message){this.success=success;this.ready=ready;this.message=message;}
    }
    private static final String RELEASES="https://api.github.com/repos/EMoyaP/BMW-E87-iDrive/releases?per_page=12";
    private static final int MAX_JSON=1_000_000,MAX_APK=80*1024*1024;
    private final Context context; private final SharedPreferences preferences; private final String installedVersion;
    AppReleaseUpdateManager(Context context){this.context=context.getApplicationContext();preferences=this.context.getSharedPreferences("e87_app_release",Context.MODE_PRIVATE);installedVersion=installedVersion(this.context);reconcile();}

    Result checkAndDownload(Progress progress){
        try{
            progress.update(97,"Comprobando la aplicación…");
            JSONArray releases=new JSONArray(readText(RELEASES));JSONObject release=null;
            for(int i=0;i<releases.length();i++){
                JSONObject candidate=releases.optJSONObject(i);String tag=candidate==null?"":candidate.optString("tag_name");
                if(candidate!=null&&!candidate.optBoolean("draft")&&!candidate.optBoolean("prerelease")
                        &&tag.startsWith("v")&&!tag.startsWith("data-v")&&newer(tag.substring(1),installedVersion)){release=candidate;break;}
            }
            if(release==null)return new Result(true,false,"Aplicación al día");
            JSONObject asset=null;JSONArray assets=release.optJSONArray("assets");
            for(int i=0;assets!=null&&i<assets.length();i++){JSONObject value=assets.optJSONObject(i);if(value!=null&&value.optString("name").endsWith(".apk")){asset=value;break;}}
            if(asset==null)throw new IOException("Release sin APK");
            String digest=asset.optString("digest","");
            if(!digest.matches("sha256:[0-9a-fA-F]{64}"))throw new IOException("Release sin SHA-256 de GitHub");
            long bytes=asset.optLong("size",0L);if(bytes<=0||bytes>MAX_APK)throw new IOException("Tamaño APK inválido");
            File root=context.getExternalFilesDir(null);if(root==null)root=context.getFilesDir();
            cleanup(root);String tag=release.optString("tag_name");File target=new File(root,"e87_update_"+tag.replaceAll("[^A-Za-z0-9._-]","_")+".apk");
            download(asset.getString("browser_download_url"),target,bytes,digest.substring(7),progress);
            preferences.edit().putString("ready_path",target.getAbsolutePath()).putString("ready_version",tag.substring(1)).apply();
            return new Result(true,true,"APP "+tag+" · TOCAR PARA INSTALAR");
        }catch(Exception error){AppSessionLog.event("ACTUALIZACIÓN APP",error.getClass().getSimpleName()+" · "+error.getMessage());return new Result(false,false,"App pendiente de reintento");}
    }

    boolean isReady(){reconcile();String path=preferences.getString("ready_path","");return !path.isEmpty()&&new File(path).isFile();}
    String readyVersion(){return preferences.getString("ready_version","");}
    void launchInstaller(){
        if(!isReady())return;
        if(Build.VERSION.SDK_INT>=26&&!context.getPackageManager().canRequestPackageInstalls()){
            Intent permission=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName()));permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(permission);return;
        }
        File file=new File(preferences.getString("ready_path",""));
        Uri uri=new Uri.Builder().scheme("content").authority("com.ug.e87idrive.diagnostic").appendPath(file.getName()).build();
        Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(install);
    }
    private void reconcile(){String version=preferences.getString("ready_version","");if(!version.isEmpty()&&!newer(version,installedVersion)){String path=preferences.getString("ready_path","");if(!path.isEmpty())new File(path).delete();preferences.edit().clear().apply();}}
    private static String installedVersion(Context context){
        try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}
        catch(Exception ignored){return "0";}
    }
    private static boolean newer(String candidate,String current){int[] a=parts(candidate),b=parts(current);for(int i=0;i<Math.max(a.length,b.length);i++){int av=i<a.length?a[i]:0,bv=i<b.length?b[i]:0;if(av!=bv)return av>bv;}return false;}
    private static int[] parts(String value){String[] tokens=value.split("[.-]");int[] result=new int[tokens.length];for(int i=0;i<tokens.length;i++)try{result[i]=Integer.parseInt(tokens[i].replaceAll("\\D.*",""));}catch(Exception ignored){}return result;}
    private static String readText(String url)throws Exception{HttpURLConnection c=open(url);ByteArrayOutputStream out=new ByteArrayOutputStream();try(BufferedInputStream in=new BufferedInputStream(c.getInputStream())){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>MAX_JSON)throw new IOException("JSON demasiado grande");out.write(b,0,n);}}finally{c.disconnect();}return out.toString("UTF-8");}
    private static void download(String url,File target,long expected,String hash,Progress progress)throws Exception{HttpURLConnection c=open(url);MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;try(BufferedInputStream in=new BufferedInputStream(c.getInputStream());FileOutputStream out=new FileOutputStream(target)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){total+=n;if(total>expected||total>MAX_APK)throw new IOException("APK demasiado grande");digest.update(b,0,n);out.write(b,0,n);progress.update(97+(int)(2L*total/expected),"Descargando aplicación…");}out.getFD().sync();}finally{c.disconnect();}if(total!=expected||!hex(digest.digest()).equalsIgnoreCase(hash)){target.delete();throw new IOException("Integridad APK incorrecta");}}
    private static HttpURLConnection open(String url)throws IOException{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(120000);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept","application/vnd.github+json, application/octet-stream");c.setRequestProperty("User-Agent","BMW-E87-iDrive/1.28");int status=c.getResponseCode();if(status<200||status>=300){c.disconnect();throw new IOException("GitHub HTTP "+status);}return c;}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    private static void cleanup(File root){File[] files=root.listFiles((dir,name)->name.startsWith("e87_update_")&&name.endsWith(".apk"));if(files!=null)for(File file:files)file.delete();}
}
