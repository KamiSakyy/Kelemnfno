package com.tsuyu.line;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.exoplayer.offline.*;
import androidx.media3.exoplayer.scheduler.*;
import java.util.List;

public final class YoruDownloadService extends DownloadService {
    private DownloadNotificationHelper notifications;
    public YoruDownloadService(){super(2101,1000L,"yoru-downloads",R.string.download_channel_name,0);}
    @Override public void onCreate(){super.onCreate();notifications=new DownloadNotificationHelper(this,"yoru-downloads");}
    @Override protected DownloadManager getDownloadManager(){DownloadHub hub=YoruApp.app().downloads();hub.applyRequirements();hub.manager.resumeDownloads();return hub.manager;}
    @Override protected Scheduler getScheduler(){return new PlatformScheduler(this,2102);}
    @Override protected Notification getForegroundNotification(List<Download> downloads,int notMetRequirements){if(notifications==null)notifications=new DownloadNotificationHelper(this,"yoru-downloads");Intent intent=new Intent(this,MainActivity.class).putExtra("openDownloads",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);PendingIntent pending=PendingIntent.getActivity(this,2103,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return notifications.buildProgressNotification(this,R.drawable.ic_download,pending,"Сохраняем серии для просмотра без интернета",downloads,notMetRequirements);}
    @Override public void onTimeout(int startId,int foregroundServiceType){YoruApp.app().downloads().manager.pauseDownloads();stopSelf();}
}
