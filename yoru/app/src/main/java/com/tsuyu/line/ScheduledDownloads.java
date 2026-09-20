package com.tsuyu.line;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.util.*;

final class ScheduledDownloads extends SQLiteOpenHelper {
    static final int JOB = 2710;
    static final long PERIOD = 15L * 60 * 1000;

    ScheduledDownloads(Context context) {
        super(context.getApplicationContext(), "scheduled-downloads.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE plans(id TEXT PRIMARY KEY, anime TEXT NOT NULL, episode REAL NOT NULL, voice TEXT NOT NULL, quality INTEGER NOT NULL, due INTEGER NOT NULL, next_check INTEGER NOT NULL, state TEXT NOT NULL, message TEXT NOT NULL, created INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    static final class Plan {
        String id, voice, state, message;
        Anime anime;
        double episode;
        int quality;
        long due, next;
        String downloadId() { return "planned-" + id; }
        String label() {
            return YoruBrain.title(anime) + " · серия " + Ui.number(episode) + "\n"
                    + (voice.isEmpty() ? "Любая доступная озвучка" : voice) + " · "
                    + (quality == QualityPlus.BEST ? "Лучшее доступное" : QualityPlus.name(quality)) + "\n" + message;
        }
    }

    List<Plan> all() {
        ArrayList<Plan> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM plans ORDER BY created", null)) {
            while (c.moveToNext()) {
                try {
                    Plan p = new Plan();
                    p.id = c.getString(c.getColumnIndexOrThrow("id"));
                    p.anime = Anime.from(new JSONObject(c.getString(c.getColumnIndexOrThrow("anime"))));
                    p.episode = c.getDouble(c.getColumnIndexOrThrow("episode"));
                    p.voice = c.getString(c.getColumnIndexOrThrow("voice"));
                    p.quality = c.getInt(c.getColumnIndexOrThrow("quality"));
                    p.due = c.getLong(c.getColumnIndexOrThrow("due"));
                    p.next = c.getLong(c.getColumnIndexOrThrow("next_check"));
                    p.state = c.getString(c.getColumnIndexOrThrow("state"));
                    p.message = c.getString(c.getColumnIndexOrThrow("message"));
                    if (Anime.valid(p.anime)) out.add(p);
                } catch (JSONException ignored) {}
            }
        }
        return out;
    }

    boolean exists(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM plans WHERE id=?", new String[]{id})) {
            return c.moveToFirst();
        }
    }

    void add(Anime anime, double episode, String voice, int quality, long due) {
        if (!Anime.valid(anime) || !Double.isFinite(episode) || episode <= 0) throw new IllegalArgumentException();
        String identity = SourceEngine.identity(anime) + "|" + Double.toString(episode);
        String id = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        if (exists(id)) throw new IllegalStateException("already planned");
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("anime", anime.json().toString());
        v.put("episode", episode);
        v.put("voice", voice);
        v.put("quality", quality);
        v.put("due", Math.max(0, due));
        v.put("next_check", Math.max(System.currentTimeMillis(), due));
        v.put("state", "waiting");
        v.put("message", "Ожидает выхода серии и выбранного варианта");
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("plans", null, v);
    }

    void update(String id, String state, String message, long next) {
        ContentValues v = new ContentValues();
        v.put("state", state);
        v.put("message", message);
        v.put("next_check", next);
        getWritableDatabase().update("plans", v, "id=?", new String[]{id});
    }

    void remove(String id) { getWritableDatabase().delete("plans", "id=?", new String[]{id}); }

    static boolean schedule(Context context) {
        Context c = context.getApplicationContext();
        try (ScheduledDownloads store = new ScheduledDownloads(c)) {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return false;
            boolean pending = false;
            for (Plan p : store.all()) if (!p.state.equals("queued")) { pending = true; break; }
            if (!pending) { js.cancel(JOB); return true; }
            JobInfo existing = js.getPendingJob(JOB);
            if (existing != null) return true;
            JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, ScheduledDownloadJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setPeriodic(PERIOD, 5L * 60 * 1000)
                    .setBackoffCriteria(PERIOD, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            return js.schedule(job)==JobScheduler.RESULT_SUCCESS;
        } catch(Exception e) { return false; }
    }

    static void choose(Activity activity, Anime anime, double episode, long due, String date) {
        if (!Anime.valid(anime)) return;
        LinearLayout col = Ui.column(activity);
        col.addView(Ui.text(activity, date == null || date.isEmpty() ? "Дата выхода уточняется" : date, 12, Ui.PURPLE, true));
        Ui.space(col,12);
        col.addView(Ui.text(activity, "Озвучка", 12, Ui.MUTED, false));
        Spinner voice = new Spinner(activity);
        String[] voices = ApiRepository.VOICE_PREF_NAMES.clone();
        voices[0] = "Любая доступная";
        voice.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, voices));
        String pref = YoruApp.app().store.voicePreference();
        for (int i=0;i<ApiRepository.VOICE_PREF_VALUES.length;i++) if (ApiRepository.VOICE_PREF_VALUES[i].equals(pref)) voice.setSelection(i);
        col.addView(voice, Ui.lp(activity,-1,48));
        col.addView(Ui.text(activity, "Качество", 12, Ui.MUTED, false));
        Spinner quality = new Spinner(activity);
        quality.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,QualityPlus.LABELS_WITH_BEST));
        quality.setSelection(QualityPlus.index(YoruApp.app().store.downloadResolution()));
        col.addView(quality,Ui.lp(activity,-1,48));
        Ui.space(col,10);
        col.addView(Ui.text(activity,"ÐÑÐ´ÐµÐ¼ Ð¶Ð´Ð°ÑÑ Ð¸Ð¼ÐµÐ½Ð½Ð¾ Ð²ÑÐ±ÑÐ°Ð½Ð½ÑÑ Ð¾Ð·Ð²ÑÑÐºÑ Ð¸ ÑÐ°Ð·ÑÐµÑÐµÐ½Ð¸Ðµ, Ð±ÐµÐ· Ð¿Ð¾Ð´Ð¼ÐµÐ½Ñ ÐÐ¾ÑÑÑÐ¿Ð½Ð¾ÑÑÑ Ð±ÑÐ´ÑÑÐµÐ¹ Ð¾Ð·Ð²ÑÑÐºÐ¸ Ð½Ðµ Ð³Ð°ÑÐ°Ð½ÑÐ¸ÑÐ¾Ð²Ð°Ð½Ð° ÐÑÐ¾Ð²ÐµÑÐºÐ¸ Ð¸Ð´ÑÑ Ð² ÑÐ¾Ð½Ðµ Ð¿ÑÐ¸ Ð½Ð°Ð»Ð¸ÑÐ¸Ð¸ ÑÐµÑÐ¸; Android Ð¼Ð¾Ð¶ÐµÑ Ð¸Ñ Ð·Ð°Ð´ÐµÑÐ¶Ð¸Ð²Ð°ÑÑ Ð£ÑÐ¸ÑÑÐ²Ð°ÐµÑÑÑ Ð½Ð°ÑÑÑÐ¾Ð¹ÐºÐ° Â«ÐÐ°Ð³ÑÑÐ·ÐºÐ¸ ÑÐ¾Ð»ÑÐºÐ¾ Ð¿Ð¾ Wi-FiÂ» ÐÐ¾ÑÐ»Ðµ Ð¿ÑÐ¸Ð½ÑÐ´Ð¸ÑÐµÐ»ÑÐ½Ð¾Ð¹ Ð¾ÑÑÐ°Ð½Ð¾Ð²ÐºÐ¸ Ð¿ÑÐ¸Ð»Ð¾Ð¶ÐµÐ½Ð¸Ñ Ð¾ÑÐºÑÐ¾Ð¹ÑÐµ Yoru ÑÐ½Ð¾Ð²Ð°",11,Ui.MUTED,false));
        Ui.custom(activity,"Скачать серию " + Ui.number(episode) + " после выхода",col,"Запланировать",()->{
            String selectedVoice = ApiRepository.VOICE_PREF_VALUES[voice.getSelectedItemPosition()];
            int selectedQuality = QualityPlus.valuesWithBest()[quality.getSelectedItemPosition()];
            Runnable save = () -> {
                if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4101);
                YoruApp.app().io.execute(()->{
                    String message;
                    try (ScheduledDownloads store = new ScheduledDownloads(activity)) {
                        store.add(anime,episode,selectedVoice,selectedQuality,due);
                        boolean scheduled=schedule(activity);
                        message=scheduled?"Запланировано Карточка задания — в разделе «Загрузки»":"ÐÐ»Ð°Ð½ ÑÐ¾ÑÑÐ°Ð½ÑÐ½, Ð½Ð¾ Android Ð½Ðµ ÑÐ°Ð·ÑÐµÑÐ¸Ð» ÑÐ¾Ð½Ð¾Ð²ÑÑ Ð¿ÑÐ¾Ð²ÐµÑÐºÑ ÐÑÐºÑÐ¾Ð¹ÑÐµ Yoru Ð¿Ð¾Ð·Ð¶Ðµ";
                    } catch (IllegalStateException e) { message="Эта серия уже запланирована Отмените задание на его карточке в «Загрузках»"; }
                    catch (Exception e) { message="Не удалось сохранить план скачивания"; }
                    String result=message;
                    YoruApp.app().main.post(()->{if(!activity.isDestroyed())Ui.toast(activity,result);});
                });
            };
            if (!YoruApp.app().store.wifiDownloads()) Ui.confirm(activity,"Автоскачивание через мобильную сеть?","Когда серия появится, загрузка может использовать мобильный интернет Ограничить её Wi-Fi можно в настройках","Запланировать",save,"Отмена");
            else save.run();
        },null,null,"Отмена");
    }

    static void showQueue(Activity activity) {
        YoruApp.app().io.execute(()->{
            List<Plan> rows;
            try (ScheduledDownloads store = new ScheduledDownloads(activity)) { rows=store.all(); }
            catch (Exception e) { YoruApp.app().main.post(()->{if(!activity.isDestroyed())Ui.toast(activity,"Не удалось прочитать очередь");}); return; }
            YoruApp.app().main.post(()->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                if(rows.isEmpty()){Ui.message(activity,"Будущие скачивания","Выберите будущую серию в карточке аниме или нажмите кнопку скачивания у события в календаре");return;}
                String[] labels=new String[rows.size()];
                for(int i=0;i<rows.size();i++)labels[i]=rows.get(i).label();
                Ui.choices(activity,"Будущие скачивания",labels,index->{
                    Plan p=rows.get(index);
                    Ui.confirm(activity,p.state.equals("queued")?"Убрать запись плана?":"Отменить ожидание серии?",p.label()+"\nУже переданная в загрузки серия управляется отдельно в списке загрузок","Убрать",()->YoruApp.app().io.execute(()->{
                        try(ScheduledDownloads store=new ScheduledDownloads(activity)){store.remove(p.id);schedule(activity);}
                        catch(Exception e){YoruApp.app().main.post(()->Ui.toast(activity,"Не удалось удалить план"));return;}
                        YoruApp.app().main.post(()->{if(!activity.isDestroyed())showQueue(activity);});
                    }),"Назад");
                });
            });
        });
    }
}
