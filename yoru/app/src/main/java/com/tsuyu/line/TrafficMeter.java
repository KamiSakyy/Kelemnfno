package com.tsuyu.line;

import android.content.Context;
import android.net.*;
import android.os.*;
import org.json.*;
import java.util.*;

public final class TrafficMeter {
    private final ConnectivityManager connectivity;private final SecureStore store;private final Handler main=new Handler(Looper.getMainLooper());
    private long mobile,wifi,other,since,lastTotal=-1,lastElapsed,lastPersist,lastNotify;private int lastKind;private boolean available=true,started,restored;
    private final ArrayList<Runnable> listeners=new ArrayList<>();
    public TrafficMeter(Context context,SecureStore s){store=s;connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);since=System.currentTimeMillis();lastKind=networkKind();try{connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){public void onAvailable(Network n){changed();}public void onLost(Network n){changed();}public void onCapabilitiesChanged(Network n,NetworkCapabilities c){changed();}});}catch(Exception ignored){}main.postDelayed(new Runnable(){public void run(){started=true;sample();notifyListeners(false);main.postDelayed(this,1500);}},500);YoruApp app=YoruApp.app();if(app!=null)app.io.execute(this::restore);}
    private synchronized void restore(){if(restored)return;restored=true;try{JSONObject j=store.traffic();mobile=Math.max(0,j.optLong("mobile"));wifi=Math.max(0,j.optLong("wifi"));other=Math.max(0,j.optLong("other"));since=j.optLong("since",since);long total=j.optLong("lastTotal",-1);long elapsed=j.optLong("lastElapsed",0);long now=SystemClock.elapsedRealtime();if(total>=0&&elapsed>0&&now>=elapsed&&now-elapsed<10*60*1000L){lastTotal=total;lastElapsed=elapsed;lastKind=j.optInt("lastKind",lastKind);}}catch(Exception ignored){}}
    private void changed(){main.post(()->{sample();notifyListeners(true);});}
    public void preferencesChanged(){changed();}
    public void addListener(Runnable r){synchronized(listeners){if(r!=null&&!listeners.contains(r))listeners.add(r);}}public void removeListener(Runnable r){synchronized(listeners){listeners.remove(r);}}
    private void notifyListeners(boolean force){long now=SystemClock.elapsedRealtime();if(!force&&now-lastNotify<1800L)return;lastNotify=now;ArrayList<Runnable> copy; synchronized(listeners){copy=new ArrayList<>(listeners);}for(Runnable r:copy)try{r.run();}catch(Exception ignored){}}
    public int networkKind(){try{Network n=connectivity.getActiveNetwork();NetworkCapabilities c=n==null?null:connectivity.getNetworkCapabilities(n);if(c==null)return 2;if(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return 0;if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return 1;}catch(Exception ignored){}return 2;}
    public boolean mobile(){return networkKind()==0;}
    public boolean metered(){try{return connectivity.isActiveNetworkMetered();}catch(Exception e){return true;}}
    public boolean connected(){try{Network n=connectivity.getActiveNetwork();NetworkCapabilities c=n==null?null:connectivity.getNetworkCapabilities(n);return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(Exception e){return false;}}
    public synchronized void sample(){long rx=TrafficStats.getUidRxBytes(android.os.Process.myUid()),tx=TrafficStats.getUidTxBytes(android.os.Process.myUid());long now=SystemClock.elapsedRealtime();int kind=networkKind();if(rx<0||tx<0){available=false;return;}available=true;long total=rx+tx;if(lastTotal>=0&&total>=lastTotal&&now>=lastElapsed){long delta=total-lastTotal;if(delta>0){int bucket=now-lastElapsed>120000?kind:lastKind;if(bucket==0)mobile+=delta;else if(bucket==1)wifi+=delta;else other+=delta;}}lastTotal=total;lastElapsed=now;lastKind=kind;if(now-lastPersist>10000L){lastPersist=now;persist();}}
    private void persist(){try{JSONObject row=new JSONObject().put("mobile",mobile).put("wifi",wifi).put("other",other).put("since",since).put("lastTotal",lastTotal).put("lastElapsed",lastElapsed).put("lastKind",lastKind);YoruApp app=YoruApp.app();if(app!=null&&app.io!=null)app.io.execute(()->{try{store.traffic(row);}catch(Exception ignored){}});else store.traffic(row);}catch(Exception ignored){}}
    public synchronized JSONObject snapshot(){sample();try{return new JSONObject().put("mobile",mobile).put("wifi",wifi).put("other",other).put("since",since).put("supported",available).put("kind",lastKind);}catch(Exception e){return new JSONObject();}}
    public synchronized void reset(){mobile=wifi=other=0;since=System.currentTimeMillis();lastTotal=-1;lastElapsed=0;lastKind=networkKind();lastPersist=0;sample();persist();notifyListeners(true);}
}
