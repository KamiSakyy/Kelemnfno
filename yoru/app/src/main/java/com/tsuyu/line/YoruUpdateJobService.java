package com.tsuyu.line;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class YoruUpdateJobService extends JobService {
    private volatile boolean cancelled;
    @Override public boolean onStartJob(JobParameters params) {
        cancelled=false;
        Thread worker=new Thread(() -> {
            boolean retry=false;
            try {
                EpisodeUpdateReceiver.CheckResult r=EpisodeUpdateReceiver.performCheck(getApplicationContext(), "job");
                retry=r!=null&&r.shouldRetry;
            } catch (Throwable ignored) { retry=true; }
            try { EpisodeUpdateReceiver.schedule(getApplicationContext()); } catch (Throwable ignored) {}
            if (!cancelled) jobFinished(params, retry);
        }, "yoru-update-job");
        worker.setPriority(Thread.NORM_PRIORITY-1);
        worker.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { cancelled=true; return true; }
}
