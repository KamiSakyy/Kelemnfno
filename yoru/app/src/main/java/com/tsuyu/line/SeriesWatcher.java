package com.tsuyu.line;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import org.json.*;
import java.util.*;

final class SeriesWatcher extends SQLiteOpenHelper {
    static final int JOB=2711;
    static final long PERIOD=15L*60*1000;
    private static volatile HashMap<String,Boolean> memo=new HashMap<>();
    private static volatile long memoAt;

    SeriesWatcher(Context context){super(context.getApplicationContext(),"series-watcher.db",null,1);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE watches(id TEXT PRIMARY KEY, anime TEXT NOT NULL, episode REAL NOT NULL, due INTEGER NOT NULL, notified INTEGER NOT NULL, created INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}

    static final class Watch {
        String id;
        Anime anime;
        double episode;
        long due;
        long created;
    }

    static String watchId(Anime anime,double episode){
        String input=SourceEngine.identity(anime)+"|"+Double.toString(episode);
        return UUID.nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    boolean watched(String id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM watches WHERE id=?",new String[]{id})){
            return c.moveToFirst();
        }catch(Exception e){return false;}
    }

    static boolean has(Anime anime,double episode){
        try{
            if(anime==null)return false;
            long now=System.currentTimeMillis();
            if(now-memoAt>2000){memo=new HashMap<>();memoAt=now;}
            String id=watchId(anime,episode);
            Boolean hit=memo.get(id);
            if(hit!=null)return hit.booleanValue();
            try(SeriesWatcher store=new SeriesWatcher(YoruApp.app())){
                boolean on=store.watched(id);
                memo.put(id,on);
                return on;
            }
        }catch(Exception e){return false;}
    }

    void add(Anime anime,double episode,long due){
        if(!Anime.valid(anime)||!Double.isFinite(episode)||episode<=0)return;
        String id=watchId(anime,episode);
        if(watched(id))return;
        ContentValues v=new ContentValues();
        v.put("id",id);
        v.put("anime",anime.json().toString());
        v.put("episode",episode);
        v.put("due",Math.max(0,due));
        v.put("notified",0);
        v.put("created",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("watches",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        memo.clear();
    }

    void forget(String id){
        getWritableDatabase().delete("watches","id=?",new String[]{id});
        memo.clear();
    }

    List<Watch> pending(){
        ArrayList<Watch> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM watches WHERE notified=0 ORDER BY created",null)){
            while(c.moveToNext()){
                try{
                    Watch w=new Watch();
                    w.id=c.getString(c.getColumnIndexOrThrow("id"));
                    w.anime=Anime.from(new JSONObject(c.getString(c.getColumnIndexOrThrow("anime"))));
                    w.episode=c.getDouble(c.getColumnIndexOrThrow("episode"));
                    w.due=c.getLong(c.getColumnIndexOrThrow("due"));
                    w.created=c.getLong(c.getColumnIndexOrThrow("created"));
                    if(Anime.valid(w.anime))out.add(w);
                }catch(JSONException ignored){}
            }
        }catch(Exception ignored){}
        return out;
    }

    static boolean schedule(Context context){
        Context c=context.getApplicationContext();
        try(SeriesWatcher store=new SeriesWatcher(c)){
            JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(js==null)return false;
            boolean pending=!store.pending().isEmpty();
            if(!pending){js.cancel(JOB);return true;}
            JobInfo existing=js.getPendingJob(JOB);
            if(existing!=null)return true;
            JobInfo job=new JobInfo.Builder(JOB,new ComponentName(c,SeriesWatchJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setPeriodic(PERIOD,5L*60*1000)
                    .setBackoffCriteria(PERIOD,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            return js.schedule(job)==JobScheduler.RESULT_SUCCESS;
        }catch(Exception e){return false;}
    }

    static void toggle(Activity activity,Anime anime,double episode,long due,Runnable changed){
        if(!Anime.valid(anime))return;
        final String id=watchId(anime,episode);
        final boolean on=!has(anime,episode);
        if(on&&Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4102);
        YoruApp.app().io.execute(()->{
            Boolean ok=null;
            try(SeriesWatcher store=new SeriesWatcher(activity)){
                if(on)store.add(anime,episode,due);
                else store.forget(id);
                ok=schedule(activity);
            }catch(Exception e){}
            final boolean failed=ok==null;
            final boolean scheduled=failed?true:ok.booleanValue();
            final String message=failed?(on?"Не удалось сохранить уведомление":"Не удалось снять уведомление"):(on?(scheduled?"Будем сообщать когда выйдет серия":"Сохранено — Android может задержать фоновую проверку"):"Уведомление снято");
            YoruApp.app().main.post(()->{
                if(!activity.isDestroyed())Ui.toast(activity,message);
                if(changed!=null)try{changed.run();}catch(Exception ignored){}
            });
        });
    }

    static void notify(Context context,Anime anime,double episode){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            if(Build.VERSION.SDK_INT>=26){
                NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
                if(nm!=null)nm.createNotificationChannel(new NotificationChannel("yoru-updates",context.getString(R.string.updates_channel_name),NotificationManager.IMPORTANCE_HIGH));
            }
            int id=watchId(anime,episode).hashCode()&0x7fffffff;
            Intent open=new Intent(context,DetailsActivity.class).putExtra("anime",anime.json().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi=PendingIntent.getActivity(context,400000+id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Intent watch=new Intent(context,PlayerActivity.class).putExtra("anime",anime.json().toString()).putExtra("episode",episode).putExtra("mode","yoru").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent watchPi=PendingIntent.getActivity(context,500000+id,watch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Bitmap poster=poster(anime.poster);
            String text=YoruBrain.title(anime)+" · серия "+Ui.number(episode);
            Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,"yoru-updates"):new Notification.Builder(context);
            builder.setSmallIcon(R.drawable.ic_download).setContentTitle("Вышла серия").setContentText(text).setContentIntent(pi).setAutoCancel(true).setShowWhen(true).setWhen(System.currentTimeMillis()).setCategory(Notification.CATEGORY_STATUS).addAction(R.drawable.ic_pip_play,"Смотреть",watchPi);
            if(Build.VERSION.SDK_INT>=21)builder.setColor(0xffa1a1aa).setOnlyAlertOnce(true);
            if(Build.VERSION.SDK_INT<26)builder.setPriority(Notification.PRIORITY_HIGH);
            if(poster!=null)builder.setLargeIcon(poster).setStyle(new Notification.BigPictureStyle().bigPicture(poster).setSummaryText(text));
            else builder.setStyle(new Notification.BigTextStyle().bigText(text+" — можно открывать просмотр"));
            NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm!=null)nm.notify(600000+id%500000,builder.build());
        }catch(Exception ignored){}
    }

    private static Bitmap poster(String url){
        String safe=ApiRepository.safeUrl(url);
        if(safe.isEmpty())return null;
        try{
            byte[] data=Net.bytes(safe,null,"image/*,*/*;q=0.8","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/149 Mobile Safari/537.36","image/",1800,2400,4*1024*1024);
            if(data.length<64)return null;
            BitmapFactory.Options o=new BitmapFactory.Options();
            o.inSampleSize=2;
            return BitmapFactory.decodeByteArray(data,0,data.length,o);
        }catch(Exception e){return null;}
    }
}
