package com.tsuyu.line;

import android.app.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.json.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public final class CalendarScreen extends FrameLayout {
    private static final int DAYS=28;
    private static final String[][] FILTERS={{"all","Все"},{"fav","Избранное"},{"new","Новые"},{"soon","Скоро"},{"premiere","Премьеры"},{"ongoing","Выходит"},{"film","Фильмы"}};
    private final Activity activity;private final RecyclerView recycler;private final LinearLayoutManager layout;private final CalendarAdapter adapter;
    private final ArrayList<ApiRepository.AiringItem> items=new ArrayList<>(),allRows=new ArrayList<>(),visible=new ArrayList<>();
    private final HashMap<String,ArrayList<ArrayList<ApiRepository.AiringItem>>> buckets=new HashMap<>();private final HashMap<String,Integer> filterCounts=new HashMap<>();
    private final Locale ru=new Locale("ru");private static final TimeZone MSK=TimeZone.getTimeZone("Europe/Moscow");private final long[] starts=new long[DAYS];private final String[] shortDays=new String[DAYS],longDays=new String[DAYS];
    private SwipeRefreshLayout swipe;
    private int selected=-1,generation,shownCount=6;private String filter="all",state="Календарь";private boolean generalUnavailable;private boolean attached=true,refreshing,personalRefreshQueued,bucketsDirty=true;private Future<?> loadFuture;
    public CalendarScreen(Activity a){super(a);activity=a;setBackgroundColor(Ui.BG);recycler=new RecyclerView(a);layout=new LinearLayoutManager(a);recycler.setLayoutManager(layout);recycler.setHasFixedSize(false);recycler.setItemViewCacheSize(18);recycler.setClipToPadding(false);recycler.setPadding(0,0,0,Ui.dp(a,16));recycler.setVerticalScrollBarEnabled(false);recycler.setHorizontalScrollBarEnabled(false);recycler.setOverScrollMode(View.OVER_SCROLL_NEVER);DefaultItemAnimator animator=new DefaultItemAnimator();animator.setAddDuration(170);animator.setMoveDuration(150);animator.setChangeDuration(120);recycler.setItemAnimator(animator);adapter=new CalendarAdapter();recycler.setAdapter(adapter);recycler.addOnScrollListener(new RecyclerView.OnScrollListener(){@Override public void onScrolled(RecyclerView v,int dx,int dy){if(dy>0)growVisible();}});swipe=new SwipeRefreshLayout(a);swipe.setColorSchemeColors(Ui.PURPLE);swipe.setProgressBackgroundColorSchemeColor(Ui.CARD);swipe.addView(recycler,new FrameLayout.LayoutParams(-1,-1));addView(swipe,new FrameLayout.LayoutParams(-1,-1));swipe.setOnRefreshListener(()->load(true));prepareDays();initCached();load(false);}
    private void initCached(){try{ArrayList<ApiRepository.AiringItem> cached=ApiRepository.airingFromJson(YoruApp.app().cache.schedule(24*60*60*1000L));if(cached.isEmpty())cached=ApiRepository.airingFromJson(readLegacyCache());if(!cached.isEmpty()){items.clear();items.addAll(cached);BucketData d=computeBuckets(cached);buckets.clear();buckets.putAll(d.buckets);filterCounts.clear();filterCounts.putAll(d.counts);bucketsDirty=false;rebuildVisible(true);adapter.publish();}}catch(Exception ignored){}}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;}
    @Override protected void onDetachedFromWindow(){attached=false;generation++;if(loadFuture!=null)loadFuture.cancel(true);super.onDetachedFromWindow();}
    private void load(){load(true);}
    private void load(boolean force){int gen=++generation;if(loadFuture!=null)loadFuture.cancel(true);prepareDays();boolean hasItems=!items.isEmpty();if(!hasItems){refreshing=true;state="Календарь";rebuildVisible(true);adapter.publish();}loadFuture=YoruApp.app().discovery.submit(()->{ArrayList<ApiRepository.AiringItem> cached=new ArrayList<>();boolean fresh=false;try{cached=ApiRepository.airingFromJson(YoruApp.app().cache.schedule(24*60*60*1000L));if(cached.isEmpty())cached=ApiRepository.airingFromJson(readLegacyCache());fresh=!cached.isEmpty()&&System.currentTimeMillis()-YoruApp.app().store.calendarCacheAt()<30*60*1000L;}catch(Exception ignored){}final ArrayList<ApiRepository.AiringItem> cacheSnapshot=new ArrayList<>(cached);if(!cacheSnapshot.isEmpty()&&items.isEmpty())YoruApp.app().main.post(()->{if(alive()&&gen==generation&&items.isEmpty())setItems(cacheSnapshot,true);});if(fresh&&!force){YoruApp.app().main.post(()->{if(alive()&&gen==generation){refreshing=false;stopSwipe();}});return;}try{ArrayList<ApiRepository.AiringItem> loaded=YoruApp.app().api.airingSchedule(DAYS,YoruApp.app().store.favorites());JSONArray json=ApiRepository.airingJson(loaded);YoruApp.app().cache.schedule(json);YoruApp.app().store.calendarCache(json);YoruApp.app().calendarTodayCount=ApiRepository.todayCount(loaded);YoruApp.app().main.post(()->{if(!alive()||gen!=generation)return;refreshing=false;stopSwipe();if(loaded.isEmpty()&&!items.isEmpty()){rebuildVisible(false);adapter.publish();}else if(!loaded.isEmpty())setItems(loaded,true);});}catch(Exception e){YoruApp.app().main.post(()->{if(!alive()||gen!=generation)return;refreshing=false;stopSwipe();if(items.isEmpty()&&!cacheSnapshot.isEmpty())setItems(cacheSnapshot,true);else{state="Календарь";rebuildVisible(false);adapter.publish();}});}});if(loadFuture.isCancelled()){refreshing=false;stopSwipe();Ui.toast(activity,"Очередь занята Нажмите обновление позже");}}
    public void quickShow(){filter="all";selected=-1;prepareDays();if(items.isEmpty())initCached();if(!items.isEmpty()){if(bucketsDirty)refreshBuckets();else{rebuildVisible(false);adapter.publish();}}if(items.isEmpty()||staleCalendar())load(false);}
    private JSONArray readLegacyCache(){try{return new JSONArray(YoruApp.app().store.calendarCache());}catch(Exception e){return new JSONArray();}}
    private boolean alive(){return attached&&!activity.isFinishing();}
    private void stopSwipe(){try{if(swipe!=null)swipe.setRefreshing(false);}catch(Exception ignored){}}
    private boolean staleCalendar(){try{return System.currentTimeMillis()-YoruApp.app().store.calendarCacheAt()>15*60*1000L;}catch(Exception e){return true;}}
    private void setItems(List<ApiRepository.AiringItem> rows,boolean keepPosition){final int gen=generation;final ArrayList<ApiRepository.AiringItem> fresh=new ArrayList<>();if(rows!=null)for(ApiRepository.AiringItem it:rows)if(it!=null&&it.anime!=null)fresh.add(it);YoruApp.app().io.execute(()->{boolean unavail=!fresh.isEmpty();for(ApiRepository.AiringItem item:fresh)if(!"personal-fallback".equals(item.source)){unavail=false;break;}BucketData d=computeBuckets(fresh);final boolean ua=unavail;YoruApp.app().main.post(()->{if(!alive()||gen!=generation)return;items.clear();items.addAll(fresh);generalUnavailable=ua;buckets.clear();buckets.putAll(d.buckets);filterCounts.clear();filterCounts.putAll(d.counts);bucketsDirty=false;if(selected>=DAYS)selected=-1;rebuildVisible(true);adapter.publish();if(!keepPosition)recycler.scrollToPosition(0);});});}
    private static final class BucketData{final HashMap<String,ArrayList<ArrayList<ApiRepository.AiringItem>>> buckets=new HashMap<>();final HashMap<String,Integer> counts=new HashMap<>();}
    private void refreshBuckets(){final int gen=generation;final ArrayList<ApiRepository.AiringItem> snapshot=new ArrayList<>(items);YoruApp.app().io.execute(()->{BucketData d=computeBuckets(snapshot);YoruApp.app().main.post(()->{if(!alive()||gen!=generation)return;buckets.clear();buckets.putAll(d.buckets);filterCounts.clear();filterCounts.putAll(d.counts);bucketsDirty=false;rebuildVisible(false);adapter.publish();});});}
    private BucketData computeBuckets(List<ApiRepository.AiringItem> src){BucketData d=new BucketData();for(String[] f:FILTERS){ArrayList<ArrayList<ApiRepository.AiringItem>> days=new ArrayList<>(DAYS);for(int j=0;j<DAYS;j++)days.add(new ArrayList<>());d.buckets.put(f[0],days);d.counts.put(f[0],0);}long now=System.currentTimeMillis(),day0=starts[0];SecureStore store=null;try{store=YoruApp.app().store;}catch(Exception ignored){}boolean personal=store!=null&&store.ready();HashMap<String,Boolean> favMemo=new HashMap<>(),historyMemo=new HashMap<>();HashSet<String> seen=new HashSet<>();for(ApiRepository.AiringItem item:src){if(item==null||item.anime==null)continue;int day=(int)((item.time-day0)/(24L*60*60*1000));if(day<0||day>=DAYS)continue;String aKey=item.anime.key();if(!seen.add(aKey+"|"+item.time+"|"+item.episode+"|"+item.kind))continue;boolean fav=false,history=false;if(personal){Boolean f=favMemo.get(aKey);if(f==null){f=store.favorite(item.anime);favMemo.put(aKey,f);}Boolean h=historyMemo.get(aKey);if(h==null){h=store.progress(item.anime).length()>0;historyMemo.put(aKey,h);}fav=f;history=h;}boolean premiere="Премьера".equals(item.kind);boolean ongoing=item.anime.ongoing()||"Новая серия".equals(item.kind);String at=item.anime.type;boolean film=at!=null&&at.toLowerCase(Locale.ROOT).contains("фильм");putBucket(d,"all",day,item);if(fav)putBucket(d,"fav",day,item);if(!history&&!fav)putBucket(d,"new",day,item);if(item.time>=now)putBucket(d,"soon",day,item);if(premiere)putBucket(d,"premiere",day,item);if(ongoing)putBucket(d,"ongoing",day,item);if(film)putBucket(d,"film",day,item);}if(!personal&&store!=null&&!personalRefreshQueued){personalRefreshQueued=true;final SecureStore st=store;YoruApp.app().io.execute(()->{try{st.preload();}catch(Exception ignored){}YoruApp.app().main.post(()->{personalRefreshQueued=false;if(!alive())return;bucketsDirty=true;refreshBuckets();});});}return d;}
    private static void putBucket(BucketData d,String key,int day,ApiRepository.AiringItem item){ArrayList<ArrayList<ApiRepository.AiringItem>> days=d.buckets.get(key);if(days!=null&&day>=0&&day<days.size()){days.get(day).add(item);d.counts.put(key,d.counts.getOrDefault(key,0)+1);}}
    private ArrayList<ApiRepository.AiringItem> dayItems(int day){ArrayList<ArrayList<ApiRepository.AiringItem>> days=buckets.get(filter);ArrayList<ApiRepository.AiringItem> out=new ArrayList<>();if(days==null)return out;if(day<0){for(ArrayList<ApiRepository.AiringItem> d:days)out.addAll(d);}else if(day<days.size())out.addAll(days.get(day));long now=System.currentTimeMillis();out.sort((x,y)->{boolean fa=x.time>=now,fb=y.time>=now;if(fa!=fb)return fa?-1:1;return fa?Long.compare(x.time,y.time):Long.compare(y.time,x.time);});return out;}
    private void rebuildVisible(boolean reset){allRows.clear();allRows.addAll(dayItems(selected));visible.clear();if(reset)shownCount=6;int total=allRows.size(),end=Math.min(shownCount,total);if(end>0)visible.addAll(allRows.subList(0,end));shownCount=end;String tail=end<total?" из "+total:"";state=selected<0?"Все события · "+end+tail:longDays[Math.max(0,Math.min(DAYS-1,selected))]+" · "+end+tail;if(generalUnavailable)state+=" · общее расписание недоступно, показана доступная часть";}private void growVisible(){if(visible.size()>=allRows.size())return;if(layout.findLastVisibleItemPosition()<adapter.getItemCount()-2)return;shownCount=visible.size()+6;rebuildVisible(false);adapter.publish();}
    private int filteredCount(){return countForFilter(filter);}
    private int countForFilter(String key){Integer n=filterCounts.get(key);return n==null?0:n;}
    private int dayCount(String key,int day){ArrayList<ArrayList<ApiRepository.AiringItem>> days=buckets.get(key);return days==null||day<0||day>=days.size()?0:days.get(day).size();}
    private int kindColor(ApiRepository.AiringItem item){if(item==null)return Ui.PURPLE;if("Премьера".equals(item.kind))return Ui.AMBER;if(item.time<System.currentTimeMillis())return Ui.EMERALD;if(item.anime!=null&&item.anime.ongoing())return Ui.PURPLE;return Ui.ZINC4;}
    private String statusSuffix(ApiRepository.AiringItem item){try{if(item!=null&&item.anime!=null){if(YoruApp.app().store.ready()&&YoruApp.app().store.favorite(item.anime))return " · в коллекции";if(item.anime.year>0)return " · "+item.anime.year;}}catch(Exception ignored){}return "";}
    private void prepareDays(){long base=todayStart();if(base==starts[0]&&shortDays[0]!=null)return;for(int i=0;i<DAYS;i++){starts[i]=base+i*24L*60*60*1000;shortDays[i]=i==0?"Сегодня":i==1?"Завтра":new SimpleDateFormat("EEE dd.MM",ru).format(new Date(starts[i])).replaceFirst("\\.","");longDays[i]=i==0?"Сегодня":i==1?"Завтра":new SimpleDateFormat("EEEE, dd MMMM",ru).format(new Date(starts[i]));}}
    private long todayStart(){Calendar c=Calendar.getInstance(MSK);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private String time(long t){SimpleDateFormat f=new SimpleDateFormat("HH:mm",ru);f.setTimeZone(MSK);return f.format(new Date(t));}
    private String countdown(long t){long diff=t-System.currentTimeMillis();if(diff<=-90*60*1000)return "уже вышло";if(diff<=0)return "выходит сейчас";long min=diff/60000,h=min/60,d=h/24;if(d>0)return "через "+d+" д "+(h%24)+" ч";if(h>0)return "через "+h+" ч "+(min%60)+" мин";return "через "+Math.max(1,min)+" мин";}
    private final class CalendarEntry {
        final int type;
        final ApiRepository.AiringItem item;
        final String key,signature;
        CalendarEntry(int type,ApiRepository.AiringItem item) {
            this.type=type;this.item=item;
            key=item==null?"section:"+type:item.anime.key()+"|"+item.episode+"|"+item.kind;
            signature=item==null?state+"|"+filter+"|"+selected+"|"+filterCounts.toString()+"|"+Arrays.toString(shortDays)+"|"+allRows.size()+"|"+visible.size():YoruBrain.title(item.anime)+"|"+item.anime.poster+"|"+item.time+"|"+item.precision+"|"+statusSuffix(item)+"|"+countdown(item.time)+"|"+SeriesWatcher.has(item.anime,item.episode);
        }
    }

    private final class CalendarAdapter extends RecyclerView.Adapter<CalendarHolder> {
        private ArrayList<CalendarEntry> rows=new ArrayList<>();
        void publish() {
            ArrayList<CalendarEntry> next=new ArrayList<>();
            next.add(new CalendarEntry(0,null));
            if(visible.isEmpty())next.add(new CalendarEntry(2,null));
            else for(ApiRepository.AiringItem item:visible)next.add(new CalendarEntry(1,item));
            ArrayList<CalendarEntry> old=rows;
            DiffUtil.DiffResult result=DiffUtil.calculateDiff(new DiffUtil.Callback() {
                public int getOldListSize(){return old.size();}
                public int getNewListSize(){return next.size();}
                public boolean areItemsTheSame(int a,int b){return old.get(a).key.equals(next.get(b).key);}
                public boolean areContentsTheSame(int a,int b){return old.get(a).signature.equals(next.get(b).signature);}
                public Object getChangePayload(int a,int b){return Boolean.TRUE;}
            });
            rows=next;result.dispatchUpdatesTo(this);
        }
        public int getItemCount(){return rows.size();}
        public int getItemViewType(int position){return rows.get(position).type;}
        public CalendarHolder onCreateViewHolder(ViewGroup parent,int type){return new CalendarHolder(type);}
        public void onBindViewHolder(CalendarHolder holder,int position){holder.bind(rows.get(position));}
        public void onBindViewHolder(CalendarHolder holder,int position,List<Object> payloads){holder.bind(rows.get(position));}
        public void onViewRecycled(CalendarHolder holder){holder.item=null;}
    }

    private final class CalendarHolder extends RecyclerView.ViewHolder {
        final FrameLayout box;
        HeaderView header;
        ImageView poster;
        TextView title,line,meta,action;
        LinearLayout icons;
        Ui.Icon bell;
        ApiRepository.AiringItem item;

        String imageKey="";
        CalendarHolder(int type) {
            super(new FrameLayout(activity));box=(FrameLayout)itemView;
            box.setPadding(Ui.dp(activity,16),0,Ui.dp(activity,16),type==0?0:Ui.dp(activity,9));
            box.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));
            if(type==0){header=new HeaderView();box.addView(header);return;}
            LinearLayout card=type==1?Ui.row(activity):Ui.column(activity);
            card.setPadding(Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12));
            card.setBackground(Ui.stroke(Ui.CARD,16,activity));box.addView(card,new FrameLayout.LayoutParams(-1,-2));
            if(type==1) {
                card.setGravity(Gravity.CENTER_VERTICAL);
                poster=new ImageView(activity);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                poster.setBackground(Ui.shape(Ui.SURFACE,12,activity));poster.setClipToOutline(true);card.addView(poster,Ui.lp(activity,54,76));
                LinearLayout text=Ui.column(activity);text.setPadding(Ui.dp(activity,12),0,0,0);
                title=Ui.text(activity,"",14,Ui.TEXT,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);text.addView(title);
                Ui.space(text,6);line=Ui.text(activity,"",11,Ui.PURPLE,true);text.addView(line);
                Ui.space(text,5);meta=Ui.text(activity,"",10,Ui.MUTED,false);meta.setSingleLine();meta.setEllipsize(TextUtils.TruncateAt.END);text.addView(meta);
                card.addView(text,new LinearLayout.LayoutParams(0,-2,1));
                View download=Ui.iconButton(activity,"download","Скачать после выхода",()->{
                    if(item==null)return;
                    SimpleDateFormat ds=new SimpleDateFormat("dd.MM.yyyy HH:mm",ru);ds.setTimeZone(MSK);ScheduledDownloads.choose(activity,item.anime,item.episode,item.time,ds.format(new Date(item.time))+" МСК · "+item.precision);
                });
                icons=Ui.column(activity);
                icons.addView(download,Ui.lp(activity,44,44));
                Ui.space(icons,5);
                bell=(Ui.Icon)Ui.iconButton(activity,"bell","Уведомлять о выходе серии",this::toggleWatch);
                icons.addView(bell,Ui.lp(activity,44,44));
                card.addView(icons,Ui.lp(activity,-2,-2));
                Ui.press(card);card.setOnClickListener(v->{if(item!=null)Ui.openDetails(activity,item.anime);});
                card.setOnLongClickListener(v->{if(item==null)return false;Ui.bucketDialog(activity,item.anime,()->{bucketsDirty=true;refreshBuckets();});return true;});
            } else {
                title=Ui.text(activity,"",15,Ui.TEXT,true);card.addView(title);
                Ui.space(card,8);line=Ui.text(activity,"",12,Ui.MUTED,false);card.addView(line);
                Ui.space(card,10);action=Ui.button(activity,"",false,()->{
                    if(items.isEmpty())load();
                    else {filter="all";selected=-1;rebuildVisible(true);adapter.publish();recycler.scrollToPosition(0);}
                });card.addView(action);
            }
        }
        void bind(CalendarEntry entry) {
            item=entry.item;
            if(entry.type==0){header.bind();return;}
            if(entry.type==1) {
                title.setText(YoruBrain.title(item.anime));line.setText(item.kind+" · серия "+Ui.number(item.episode)+" · "+time(item.time)+" МСК");line.setTextColor(kindColor(item));
                meta.setText(countdown(item.time)+" · "+item.precision+statusSuffix(item));
                String key=item.anime.key()+"|"+item.anime.poster;
                if(!key.equals(imageKey)){imageKey=key;YoruApp.app().images.load(poster,item.anime);}
                boolean watched=SeriesWatcher.has(item.anime,item.episode);
                bell.filled=watched;
                bell.color=watched?Ui.PURPLE:Ui.MUTED;
                bell.invalidate();
                bell.setContentDescription(watched?"Уведомление о выходе серии включено":"Включить уведомление о выходе серии");
                box.setContentDescription(YoruBrain.title(item.anime)+". "+line.getText()+". "+meta.getText());
            } else if(entry.type==2) {
                title.setText(items.isEmpty()?"Пока нет событий":"В этом фильтре пока пусто");
                line.setText(items.isEmpty()?"События появятся автоматически":"Выберите другой фильтр, другой день или вернитесь к режиму «Все»");
                action.setText(items.isEmpty()?"Обновить":"Показать все");action.setVisibility(View.VISIBLE);
            }
        }
        void toggleWatch() {
            if(item==null)return;
            SeriesWatcher.toggle(activity,item.anime,item.episode,item.time,()->{bucketsDirty=true;refreshBuckets();});
        }
    }

    private final class HeaderView extends LinearLayout {
        final TextView status,all;
        final TextView[] days=new TextView[DAYS],filters=new TextView[FILTERS.length];
        HeaderView() {
            super(activity);setOrientation(VERTICAL);setPadding(0,Ui.dp(activity,12),0,Ui.dp(activity,10));
            addView(Ui.label(activity,"КАЛЕНДАРЬ Tsuyu"));Ui.space(this,9);addView(Ui.text(activity,"Выход серий",28,Ui.TEXT,true));
            Ui.space(this,7);addView(Ui.text(activity,"Все ближайшие аниме одним списком · время — МСК",12,Ui.MUTED,false));Ui.space(this,12);
            Ui.space(this,13);LinearLayout dayRow=horizontal();
            all=chip(dayRow,()->{if(selected<0)return;selected=-1;select();});
            for(int i=0;i<DAYS;i++){final int index=i;days[i]=chip(dayRow,()->{if(selected==index)return;selected=index;select();});}
            Ui.space(this,9);LinearLayout filterRow=horizontal();
            for(int i=0;i<FILTERS.length;i++){final String value=FILTERS[i][0];filters[i]=chip(filterRow,()->{if(value.equals(filter))return;filter=value;selected=-1;select();});}
            Ui.space(this,10);status=Ui.text(activity,"",12,Ui.MUTED,false);addView(status);
        }
        LinearLayout horizontal(){HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);LinearLayout row=Ui.row(activity);scroll.addView(row);addView(scroll,Ui.lp(activity,-1,-2));return row;}
        TextView chip(LinearLayout row,Runnable click){TextView view=Ui.chip(activity,"",false,click);LinearLayout.LayoutParams p=Ui.lp(activity,-2,-2);p.rightMargin=Ui.dp(activity,7);row.addView(view,p);return view;}
        void select(){rebuildVisible(true);adapter.publish();recycler.scrollToPosition(0);}
        void style(TextView view,String value,boolean active){view.setText(value);view.setTextColor(active?0xff21152f:Ui.MUTED);view.setBackground(active?Ui.gradient(0xffe2ccff,Ui.PURPLE,13,activity):Ui.stroke(Ui.SURFACE,13,activity));view.setSelected(active);}
        void bind(){
            status.setText(state);
            style(all,"Все дни · "+filteredCount(),selected<0);
            for(int i=0;i<DAYS;i++)style(days[i],shortDays[i]+" · "+dayCount(filter,i),selected==i);
            for(int i=0;i<FILTERS.length;i++)style(filters[i],FILTERS[i][1]+" · "+countForFilter(FILTERS[i][0]),FILTERS[i][0].equals(filter));
        }
    }
}
