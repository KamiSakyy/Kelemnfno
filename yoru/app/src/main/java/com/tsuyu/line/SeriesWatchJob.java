package com.tsuyu.line;

import android.app.job.*;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class SeriesWatchJob extends JobService {
    private volatile Thread worker;
    private volatile AtomicBoolean cancelled;

    @Override public boolean onStartJob(JobParameters params) {
        if(worker!=null)return false;
        AtomicBoolean stop=new AtomicBoolean(false);
        cancelled=stop;
        worker=new Thread(()->{
            try{TaskQueue.attach(stop);checkWatches(stop);}
            catch(Exception ignored){}
            finally{
                TaskQueue.detach();
                worker=null;
                YoruApp.app().main.post(()->{
                    if(!stop.get()){
                        jobFinished(params,false);
                        YoruApp.app().discovery.execute(()->SeriesWatcher.schedule(this));
                    }
                });
            }
        },"yoru-series-watch");
        worker.setPriority(Thread.NORM_PRIORITY-1);
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if(cancelled!=null)cancelled.set(true);
        Thread t=worker;
        if(t!=null)t.interrupt();
        return true;
    }

    private void checkWatches(AtomicBoolean stop){
        if(YoruGuard.blocked())return;
        YoruApp app=YoruApp.app();
        long deadline=SystemClock.elapsedRealtime()+70_000;
        long now=System.currentTimeMillis();
        List<SeriesWatcher.Watch> rows=new ArrayList<>();
        try(SeriesWatcher store=new SeriesWatcher(this)){rows=store.pending();}
        catch(Exception e){return;}
        int handled=0;
        for(SeriesWatcher.Watch w:rows){
            try{TaskQueue.check();}
            catch(java.io.InterruptedIOException iie){Thread.currentThread().interrupt();return;}
            if(stop.get()||SystemClock.elapsedRealtime()>=deadline||handled>=3)return;
            if(w.due>now+12L*60*60*1000L)continue;
            int aired=-1;
            try{aired=app.api.airedEpisodes(w.anime);}
            catch(Exception ignored){}
            if(stop.get()||SystemClock.elapsedRealtime()>=deadline)return;
            if(aired>0&&aired<w.episode-0.001)continue;
            try{
                boolean confirmed=aired>=0&&aired>=w.episode-0.001;
                if(!confirmed){
                    Anime fresh=app.api.details(w.anime,true);
                    Anime.Episode exact=null;
                    for(Anime.Episode x:fresh.episodeList)if(x!=null&&!x.future&&Math.abs(x.number-w.episode)<0.001){exact=x;break;}
                    if(exact!=null){app.api.loadEpisode(exact);confirmed=!exact.streams.isEmpty()||!exact.variants.isEmpty();}
                }
                try{TaskQueue.check();}
                catch(java.io.InterruptedIOException iie){Thread.currentThread().interrupt();return;}
                if(stop.get()||SystemClock.elapsedRealtime()>=deadline)return;
                if(!confirmed)continue;
                handled++;
                SeriesWatcher.notify(this,w.anime,w.episode);
                try(SeriesWatcher store=new SeriesWatcher(this)){store.forget(SeriesWatcher.watchId(w.anime,w.episode));}
            }catch(Exception ignored){}
        }
    }
}
