package com.tsuyu.line;

import android.app.*;
import android.os.*;
import android.webkit.WebView;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class YoruApp extends Application {
    public static YoruApp instance;
    public final ExecutorService io=ioPool(),ui=uiPool(),discovery=discoveryPool();
    public final Handler main=new Handler(Looper.getMainLooper());
    public SecureStore store;
    public YoruCache cache;
    public ApiRepository api;
    public ImageLoader images;
    public boolean original;
    public TrafficMeter traffic;
    public MediaCache mediaCache;
    private DownloadHub downloads;
    public volatile int activePlayers,calendarTodayCount;private volatile boolean warmed;
    private static ExecutorService pool(String name,int core,int max,int priority){ThreadPoolExecutor e=new TaskQueue.Executor(core,max,20L,TimeUnit.SECONDS,new LinkedBlockingQueue<>(512),r->{Thread t=new Thread(r,name);t.setPriority(priority);return t;},new TaskQueue.Policy());e.allowCoreThreadTimeOut(true);return e;}
    private static ExecutorService ioPool(){int cores=Math.max(4,Runtime.getRuntime().availableProcessors());return pool("yoru-io",Math.max(32,cores*8),Math.max(64,cores*16),Thread.NORM_PRIORITY);}
    private static ExecutorService uiPool(){int cores=Math.max(4,Runtime.getRuntime().availableProcessors());return pool("yoru-ui",Math.max(16,cores*4),Math.max(32,cores*8),Thread.NORM_PRIORITY+1);}
    private static ExecutorService discoveryPool(){int cores=Math.max(2,Runtime.getRuntime().availableProcessors());return pool("yoru-bg",Math.max(8,cores*2),Math.max(20,cores*4),Thread.NORM_PRIORITY-1);}
    public synchronized DownloadHub downloads(){if(downloads==null)downloads=new DownloadHub(this,mediaCache);return downloads;}
    public void focusNow(String screen){Net.focus(screen);if(!"details".equals(screen)&&!"player".equals(screen))return;try{if(discovery instanceof java.util.concurrent.ThreadPoolExecutor){java.util.concurrent.ThreadPoolExecutor pool=(java.util.concurrent.ThreadPoolExecutor)discovery;for(Runnable task:pool.getQueue().toArray(new Runnable[0]))if(task instanceof Future)try{((Future<?>)task).cancel(false);}catch(Exception ignored){}pool.purge();}}catch(Exception ignored){}}
    public boolean savingMobile(){return store!=null&&traffic!=null&&store.dataSaver()&&traffic.mobile();}
    public boolean autoNextAllowed(){return store.autoNext();}
    @Override public void onCreate(){super.onCreate();instance=this;initShield();YoruGuard.arm(this);original=true;store=new SecureStore(this);cache=new YoruCache(this);traffic=new TrafficMeter(this,store);mediaCache=new MediaCache(this);api=new ApiRepository(this);images=new ImageLoader(this);ServiceLocator.init(this);channels();io.execute(()->{try{store.preload();}catch(Exception ignored){}});main.post(this::afterFirstFrame);}
    private void initShield(){
        if(!BuildConfig.DEBUG){
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t,e)->{
                try { android.util.Log.e("YoruShield", "uncaught", e); } catch (Throwable ignored) {}
                try { if (prev != null) prev.uncaughtException(t,e); } catch (Throwable ignored) {}
                // do NOT kill instantly to avoid вылет без логов
                // let system handle
            });
        }
    }
    private void afterFirstFrame(){try{WebView.setWebContentsDebuggingEnabled(false);}catch(Exception ignored){}discovery.execute(()->{try{EpisodeUpdateReceiver.schedule(this);ScheduledDownloads.schedule(this);SeriesWatcher.schedule(this);if(System.currentTimeMillis()-store.lastEpisodeCheckAt()>35L*60L*1000L)EpisodeUpdateReceiver.checkSoon(this);}catch(Exception ignored){}});io.execute(()->{try{api.refreshProtection(false);}catch(Exception ignored){}});warmStartup();}
    public void warmStartup(){if(warmed)return;warmed=true;discovery.execute(()->Net.runBg(()->{try{calendarTodayCount=cache==null?0:cache.todayScheduleCount();}catch(Exception ignored){calendarTodayCount=0;}try{api.seed();}catch(Exception ignored){}try{long at=store.calendarCacheAt();if(System.currentTimeMillis()-at<25*60*1000L&&calendarTodayCount>0)return;ArrayList<ApiRepository.AiringItem> rows=api.airingSchedule(28,store.favorites());JSONArray json=ApiRepository.airingJson(rows);if(cache!=null)cache.schedule(json);store.calendarCache(json);calendarTodayCount=ApiRepository.todayCount(rows);}catch(Exception ignored){}}));io.execute(()->{try{if(cache!=null)cache.trimNow();}catch(Exception ignored){}});}
    private void channels(){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm!=null)nm.createNotificationChannel(new NotificationChannel("yoru-updates",getString(R.string.updates_channel_name),NotificationManager.IMPORTANCE_HIGH));}}
    public static YoruApp app(){return instance;}
}
