package com.tsuyu.line;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HentaiEngine {
    private static final String[] SITES={"hs","ah","hk","an","he","hn","pc","htv"};
    private static final String FIELDS=ShikimoriApi.SH_FIELDS + " descriptionHtml";
    private final ApiRepository api;
    private static final ExecutorService PROBE_POOL=Executors.newFixedThreadPool(10);

    public HentaiEngine(ApiRepository api){this.api=api;}

    public static boolean isHenta(){return "henta".equals(BuildConfig.FLAVOR);}

    private static String host(String site){if(site.equals("hs"))return "https://hentasis1.top";if(site.equals("ah"))return "https://allhentaii.fun";if(site.equals("hk"))return "https://v6.hentakli.org";return "https://porncado.com";}

    private static int digit(String site){if(site.equals("hs"))return 1;if(site.equals("ah"))return 2;if(site.equals("hk"))return 3;if(site.equals("hn"))return 5;if(site.equals("an"))return 6;if(site.equals("he"))return 7;if(site.equals("htv"))return 8;return 4;}

    private static String siteOf(char c){if(c=='1')return "hs";if(c=='2')return "ah";if(c=='3')return "hk";if(c=='5')return "hn";if(c=='6')return "an";if(c=='7')return "he";if(c=='8')return "htv";return "pc";}

    private static final class StreamPick{
        String url="";
        int h=0;
    }

    private List<Row> rowsHanime(int page)throws Exception{
        JSONObject body=hanimeSearchBody("",Math.max(0,page-1));
        JSONObject data=new JSONObject(api.request("https://search.htv-services.com/","POST",body.toString(),false));
        return parseHanimeSearch(data);
    }

    private List<Row> rowsHanimeSearch(String q)throws Exception{
        JSONObject body=hanimeSearchBody(q,0);
        JSONObject data=new JSONObject(api.request("https://search.htv-services.com/","POST",body.toString(),false));
        return parseHanimeSearch(data);
    }

    private static JSONObject hanimeSearchBody(String q,int page)throws Exception{
        return new JSONObject().put("search_text",q).put("tags",new JSONArray()).put("tags_mode","AND").put("brands",new JSONArray()).put("blacklist",new JSONArray()).put("order_by","likes").put("ordering","desc").put("page",page);
    }

    private List<Row> parseHanimeSearch(JSONObject data){
        List<Row> out=new ArrayList<>();
        JSONArray rows=null;
        for(String k:new String[]{"videos","data","items","results"}){
            rows=data.optJSONArray(k);
            if(rows!=null)break;
        }
        if(rows==null)return out;
        for(int i=0;i<rows.length();i++){
            JSONObject v=rows.optJSONObject(i);
            if(v==null)continue;
            String slug=v.optString("slug","");
            String name=v.optString("name","");
            if(slug.isEmpty()||name.length()<4)continue;
            long id=v.optLong("id",0);
            if(id<=0)id=100000+i;
            JSONArray titles=v.optJSONArray("titles");
            String orig=titles==null?"":titles.optString(0,"");
            out.add(new Row("hn",String.valueOf(id),name,orig,ApiRepository.safeUrl(v.optString("poster_url",v.optString("cover_url",""))),slug,slug,hanimeYear(v.optLong("released_at",0))));
        }
        return out;
    }

    private static int hanimeYear(long ts){
        if(ts<=0)return 0;
        long sec=ts>100000000000L?ts/1000:ts;
        return 1970+(int)(sec/31556952L);
    }

    private JSONObject hanimeVideoJson(String slug)throws Exception{
        String enc=java.net.URLEncoder.encode(slug,"UTF-8");
        try{return new JSONObject(api.request("https://members.hanime.tv/rapi/v7/video?id="+enc,"GET",null,false));}
        catch(Exception e){return new JSONObject(api.request("https://hanime.tv/api/v8/video?id="+enc,"GET",null,false));}
    }

    private void pickStream(JSONObject d,StreamPick pick){
        if(d==null)return;
        String u=d.optString("url",d.optString("src",""));
        int h=streamHeight(d);
        if(u.startsWith("http")&&(h<=0||h<=720)&&h>=pick.h){pick.url=u;pick.h=h;}
        java.util.Iterator<String> it=d.keys();
        while(it.hasNext()){
            Object v=d.opt(it.next());
            if(v instanceof JSONObject)pickStream((JSONObject)v,pick);
            else if(v instanceof JSONArray){
                JSONArray arr=(JSONArray)v;
                for(int i=0;i<arr.length();i++){
                    Object x=arr.opt(i);
                    if(x instanceof JSONObject)pickStream((JSONObject)x,pick);
                }
            }
        }
    }

    private static int streamHeight(JSONObject st){
        int h=st.optInt("height",st.optInt("resolution",0));
        if(h<=0){
            String s=String.valueOf(st.opt("height"));
            StringBuilder d=new StringBuilder();
            for(int i=0;i<s.length();i++){
                char c=s.charAt(i);
                if(c>='0'&&c<='9')d.append(c);
            }
            if(d.length()>0&&d.length()<5)try{h=Integer.parseInt(d.toString());}catch(Exception ignored){}
        }
        return h<0?0:h;
    }

    private void fillHanime(Anime a,String slug)throws Exception{
        JSONObject info=hanimeVideoJson(slug);
        JSONObject hv=null;
        for(String k:new String[]{"hentai_video","video","data"}){
            hv=info.optJSONObject(k);
            if(hv!=null)break;
        }
        JSONObject v=hv!=null?hv:info;
        if(a.poster.isEmpty())a.poster=ApiRepository.safeUrl(v.optString("poster_url",v.optString("cover_url","")));
        ArrayList<String> slugs=new ArrayList<>();
        for(String k:new String[]{"hentai_franchise_hentai_videos","franchise_videos","videos","episodes"}){
            JSONArray fr=info.optJSONArray(k);
            if(fr!=null){
                for(int i=0;i<fr.length();i++){
                    JSONObject x=fr.optJSONObject(i);
                    if(x!=null&&!x.optString("slug","").isEmpty())slugs.add(x.optString("slug",""));
                }
                break;
            }
        }
        if(slugs.isEmpty()&&!v.optString("slug","").isEmpty())slugs.add(v.optString("slug",""));
        int n=0;
        for(String s2:slugs){
            if(n>=24)break;
            JSONObject d=null;
            try{d=hanimeVideoJson(s2);}catch(Exception ignored){}
            if(d==null)continue;
            StreamPick pick=new StreamPick();
            pickStream(d,pick);
            if(pick.url.isEmpty())continue;
            n++;
            Anime.Episode e=new Anime.Episode();
            e.id="hn-"+n;
            e.number=n;
            e.lazy="henta";
            e.variants.add(0,new Anime.Variant("Оригинал","Hanime",pick.url));
            a.episodeList.add(e);
        }
    }

    private static final class Row{
        final String site,sid,title,orig,poster,url,ref;final int year;
        Row(String site,String sid,String title,String orig,String poster,String url,String ref,int year){this.site=site;this.sid=sid;this.title=title;this.orig=orig;this.poster=poster;this.url=url;this.ref=ref;this.year=year;}
    }

    public Anime.Page catalog(String search,int page,ApiRepository.Filter f)throws Exception{
        String q=search==null?"":search.trim().toLowerCase(Locale.ROOT);
        String ck=q.isEmpty()?("henta:cat:"+Math.max(1,page)):("henta:sea:"+Math.max(1,page)+":"+q);
        YoruCache db=YoruApp.app()==null?null:YoruApp.app().cache;
        if(db!=null){try{JSONArray saved=db.hentaJson(ck,24*60*60*1000L);if(saved!=null){Anime.Page p=fromJson(saved);if(p!=null&&!p.items.isEmpty())return p;}}catch(Exception ignored){}}
        Anime.Page out=q.isEmpty()?catalogBrowsed(Math.max(1,page)):catalogSearched(q,Math.max(1,page));
        if(db!=null&&!out.items.isEmpty()){try{db.hentaPut(ck,toJson(out),24*60*60*1000L);}catch(Exception ignored){}}
        return out;
    }

    private static String numId(String s){
        long h=1125899906842597L;
        for(int i=0;i<s.length();i++)h=31*h+s.charAt(i);
        h=Math.abs(h);
        return String.valueOf(1000000000L+h%8000000000L);
    }

    private static String xmlGroup(String block,String open,String close){
        int i=block.indexOf(open);
        if(i<0)return null;
        int j=block.indexOf(close,i+open.length());
        if(j<0)return null;
        return block.substring(i+open.length(),j).trim();
    }

    private List<Row> rowsAnihentai(int page)throws Exception{
        List<Row> out=new ArrayList<>();
        String text=api.hentaText("https://animeidhentai.com/api/browse?page="+Math.max(1,page));
        JSONObject data=new JSONObject(text);
        JSONArray vids=data.optJSONArray("videos");
        if(vids==null)return out;
        LinkedHashMap<String,JSONObject> byTitle=new LinkedHashMap<>();
        for(int i=0;i<vids.length();i++){
            JSONObject v=vids.optJSONObject(i);
            if(v==null)continue;
            String ts=v.optString("titleSlug","");
            if(ts.length()<4)continue;
            JSONObject cur=byTitle.get(ts);
            if(cur==null||v.optInt("ep",99)<cur.optInt("ep",99))byTitle.put(ts,v);
        }
        int n=0;
        for(String ts:byTitle.keySet()){
            if(n++>=36)break;
            JSONObject v=byTitle.get(ts);
            String embed=v.optString("embedUrl","");
            if(embed.isEmpty())continue;
            String poster=v.optString("cover","");
            if(poster.startsWith("/")&&!poster.startsWith("//"))poster="https://animeidhentai.com"+poster;
            out.add(new Row("an",numId(ts),v.optString("title",""),v.optString("title",""),ApiRepository.safeUrl(poster),embed,ts,v.optInt("year",0)));
        }
        return out;
    }

    private List<Row> rowsAnihentaiSearch(String q)throws Exception{
        List<Row> out=new ArrayList<>();
        if(q==null||q.trim().length()<3)return out;
        String u="https://animeidhentai.com/api/search?q="+java.net.URLEncoder.encode(q.trim(),"UTF-8")+"&limit=20";
        JSONObject data=new JSONObject(api.hentaText(u));
        JSONArray vids=data.optJSONArray("videos");
        if(vids==null)return out;
        LinkedHashMap<String,JSONObject> byTitle=new LinkedHashMap<>();
        for(int i=0;i<vids.length();i++){
            JSONObject v=vids.optJSONObject(i);
            if(v==null)continue;
            String ts=v.optString("titleSlug","");
            if(ts.length()<4)continue;
            JSONObject cur=byTitle.get(ts);
            if(cur==null||v.optInt("ep",99)<cur.optInt("ep",99))byTitle.put(ts,v);
        }
        for(String ts:byTitle.keySet()){
            if(out.size()>=12)break;
            JSONObject v=byTitle.get(ts);
            String embed=v.optString("embedUrl","");
            if(embed.isEmpty())continue;
            String poster=v.optString("cover","");
            if(poster.startsWith("/")&&!poster.startsWith("//"))poster="https://animeidhentai.com"+poster;
            out.add(new Row("an",numId(ts),v.optString("title",""),v.optString("title",""),ApiRepository.safeUrl(poster),embed,ts,v.optInt("year",0)));
        }
        return out;
    }

    private static final Pattern HTV_CARD=Pattern.compile("\\*\"title\\*\":\\*\"([^\"\\\\]{3,120})\\*\",\\*\"titleSlug\\*\":\\*\"([a-z0-9-]+)\\*\",\\*\"titleId\\*\":\\*\"[^\\\\]+\\*\",\\*\"ep\\*\":(\\d+)[^{}]*?\\*\"cover\\*\":\\*\"(/uploads/[a-zA-Z0-9._/-]+)\\*\"[^{}]*?\\*\"embedUrl\\*\":\\*\"https://nhplayer\\.com/v/([A-Za-z0-9]+)/");

    private List<Row> rowsHtv(int page)throws Exception{
        List<Row> out=new ArrayList<>();
        String html=api.request("https://hentai.tv/","GET",null,false);
        LinkedHashMap<String,Row> map=new LinkedHashMap<>();
        Matcher m=HTV_CARD.matcher(html);
        while(m.find()){
            String slug=m.group(2);
            if(map.containsKey(slug))continue;
            String cover=m.group(4);
            if(cover.startsWith("/"))cover="https://hentai.tv"+cover;
            map.put(slug,new Row("htv",numId(slug),unesc(m.group(1)),unesc(m.group(1)),ApiRepository.safeUrl(cover),"https://nhplayer.com/v/"+m.group(5)+"/",slug,0));
        }
        for(Row r:map.values())out.add(r);
        return out;
    }

    private String htvFile(String embedUrl)throws Exception{
        String html=api.hentaText(embedUrl);
        Matcher m=Pattern.compile("vid=([A-Za-z0-9+/=]+)").matcher(html);
        if(!m.find())return null;
        String dec=new String(Base64.getDecoder().decode(m.group(1)),"UTF-8");
        int bar=dec.indexOf('|');
        String u=bar>0?dec.substring(0,bar):dec;
        return u.startsWith("http")?u:null;
    }

    private List<Row> rowsHentaistream(int page)throws Exception{
        List<Row> out=new ArrayList<>();
        String html=api.request("https://tube.hentaistream.com/hentai-series-list-full-shows","GET",null,false,heHeaders());
        Pattern p=Pattern.compile("<a[^>]+href=\"https://tube\\.hentaistream\\.com/hentaidvd/([a-z0-9-]+)\"[^>]*>([^<]{4,200})</a>");
        Matcher m=p.matcher(html);
        LinkedHashMap<String,Row> map=new LinkedHashMap<>();
        while(m.find()){
            String slug=m.group(1);
            String title=unesc(m.group(2)).trim();
            if(title.length()<4||map.containsKey(slug))continue;
            String poster=hentaCoverFallback(title,title,slug);
            map.put(slug,new Row("he",numId(slug),title,title,poster,"https://tube.hentaistream.com/hentaidvd/"+slug,slug,0));
        }
        ArrayList<Row> all=new ArrayList<>(map.values());
        int start=(Math.max(1,page)-1)*36;
        for(int i=start;i<all.size()&&i<start+36;i++)out.add(all.get(i));
        return out;
    }

    private String anFile(String embedUrl)throws Exception{
        String html=api.hentaText(embedUrl);
        Matcher m=Pattern.compile("vid=([A-Za-z0-9+/=]+)").matcher(html);
        if(!m.find())return null;
        String dec=new String(Base64.getDecoder().decode(m.group(1)),"UTF-8");
        int bar=dec.indexOf('|');
        String u=bar>0?dec.substring(0,bar):dec;
        return u.startsWith("http")?u:null;
    }

    private static java.util.Map<String,String> heHeaders(){
        java.util.HashMap<String,String> h=new java.util.HashMap<>();
        h.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
        h.put("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        return h;
    }

    private String hentaistreamFile(String episodeUrl)throws Exception{
        String html=api.request(episodeUrl,"GET",null,false,heHeaders());
        Matcher m=Pattern.compile("frames/(s[0-9]+[^'\\s<>]+?)\\.html").matcher(html);
        ArrayList<String> tried=new ArrayList<>();
        while(m.find()&&tried.size()<3){
            String base="https://tube.hentaistream.com/frames/"+m.group(1)+".html";
            if(!tried.contains(base))tried.add(base);
            if(!tried.contains(base+".html"))tried.add(base+".html");
        }
        for(String u:tried){
            try{
                String page=api.request(u,"GET",null,false,heHeaders());
                Matcher f=Pattern.compile("https://cdn[0-9]*\\.streamhentai\\.org/[A-Za-z0-9._%-]+\\.mp4").matcher(page);
                if(f.find())return f.group(0);
            }catch(Exception ignored){}
        }
        return null;
    }

    private void fillAnihentai(Anime a)throws Exception{
        String slug=a.hentaUrl;
        if(slug==null||slug.length()<4)return;
        ArrayList<JSONObject> eps=new ArrayList<>();
        ExecutorService pool=Executors.newFixedThreadPool(4);
        CompletionService<JSONArray> done=new ExecutorCompletionService<>(pool);
        for(int p=1;p<=4;p++){
            final int page=p;
            done.submit(()->{
                try{
                    JSONObject d=new JSONObject(api.hentaText("https://animeidhentai.com/api/browse?page="+page));
                    return d.optJSONArray("videos");
                }catch(Exception e){return null;}
            });
        }
        long deadline=System.currentTimeMillis()+12000;
        try{
            for(int i=0;i<4;i++){
                long left=deadline-System.currentTimeMillis();
                if(left<=0)break;
                JSONArray arr=null;
                try{arr=done.poll(left,TimeUnit.MILLISECONDS).get(left,TimeUnit.MILLISECONDS);}catch(Exception e){}
                if(arr==null)continue;
                for(int i2=0;i2<arr.length();i2++){
                    JSONObject v=arr.optJSONObject(i2);
                    if(v!=null&&slug.equals(v.optString("titleSlug",""))&&!eps.contains(v))eps.add(v);
                }
            }
        }finally{pool.shutdownNow();}
        Collections.sort(eps,new java.util.Comparator<JSONObject>(){
            @Override public int compare(JSONObject x,JSONObject y){return Integer.compare(x.optInt("ep",0),y.optInt("ep",0));}
        });
        int n=0;
        for(JSONObject v:eps){
            if(n>=16)break;
            String embed=v.optString("embedUrl","");
            if(embed.isEmpty())continue;
            String file=null;
            try{file=anFile(embed);}catch(Exception ignored){}
            if(file==null||file.isEmpty())continue;
            n++;
            Anime.Episode e=new Anime.Episode();
            e.id="an-"+n;
            e.number=n;
            e.lazy="henta";
            String lang=v.optString("language","");
            e.variants.add(0,new Anime.Variant(lang.contains("Sub")?"Суб":"Оригинал","Tsuyu",file));
            a.episodeList.add(e);
        }
    }

    private void fillHtv(Anime a)throws Exception{
        String slug=a.hentaUrl;
        if(slug==null||slug.length()<4)return;
        String html=api.request("https://hentai.tv/series/"+slug,"GET",null,false);
        LinkedHashMap<Integer,Row> byEp=new LinkedHashMap<>();
        Matcher m=HTV_CARD.matcher(html);
        while(m.find()){
            if(!slug.equals(m.group(2)))continue;
            int ep;
            try{ep=Integer.parseInt(m.group(3));}catch(Exception e){continue;}
            String cover=m.group(4);
            if(cover.startsWith("/"))cover="https://hentai.tv"+cover;
            byEp.putIfAbsent(ep,new Row("htv",String.valueOf(ep),unesc(m.group(1)),unesc(m.group(1)),ApiRepository.safeUrl(cover),"https://nhplayer.com/v/"+m.group(5)+"/",slug,0));
        }
        ArrayList<Row> list=new ArrayList<>();
        ArrayList<Integer> order=new ArrayList<>(byEp.keySet());
        Collections.sort(order);
        for(int n:order){if(list.size()>=16)break;list.add(byEp.get(n));}
        if(list.isEmpty())return;
        ExecutorService pool=Executors.newFixedThreadPool(4);
        LinkedHashMap<Row,Future<String>> jobs=new LinkedHashMap<>();
        try{
            for(Row r:list)jobs.put(r,pool.submit(()->{try{return htvFile(r.url);}catch(Exception e){return null;}}));
            int n=0;
            for(java.util.Map.Entry<Row,Future<String>> en:jobs.entrySet()){
                String file=null;
                try{file=en.getValue().get(20000,TimeUnit.MILLISECONDS);}catch(Exception e){}
                if(file==null||file.isEmpty())continue;
                n++;
                Anime.Episode e=new Anime.Episode();
                e.id="htv-"+n;
                e.number=Integer.parseInt(en.getKey().sid);
                e.lazy="henta";
                e.variants.add(0,new Anime.Variant("Суб","Tsuyu",file));
                a.episodeList.add(e);
            }
        }finally{pool.shutdownNow();}
    }

    private void fillHentaistream(Anime a)throws Exception{
        String slug=a.hentaUrl;
        if(slug==null||slug.length()<4)return;
        String url=slug.contains("http")?slug:"https://tube.hentaistream.com/hentaidvd/"+slug;
        String html=api.request(url,"GET",null,false,heHeaders());
        Matcher pm=Pattern.compile("<img[^>]+(?:src|data-src)=[\"']?([^\"'\\s>]+)").matcher(html);
        if(pm.find()){
            String poster=pm.group(1);
            if(poster.length()>12)a.poster=ApiRepository.safeUrl(poster);
        }
        if(a.poster==null||a.poster.isEmpty()){
            a.poster=hentaCoverFallback(a.title,a.original,a.hentaUrl);
        }
        Pattern p=Pattern.compile("https://tube\\.hentaistream\\.com/([a-z0-9-]+)-episode-(\\d+)");
        Matcher m=p.matcher(html);
        LinkedHashMap<Integer,String> eps=new LinkedHashMap<>();
        while(m.find()){
            if(!slug.equals(m.group(1)))continue;
            eps.putIfAbsent(Integer.parseInt(m.group(2)),m.group(0));
        }
        ArrayList<Integer> order=new ArrayList<>(eps.keySet());
        Collections.sort(order);
        int n=0;
        for(int nn:order){
            if(n>=24)break;
            String file=null;
            try{file=hentaistreamFile(eps.get(nn));}catch(Exception ignored){}
            if(file==null||file.isEmpty())continue;
            n++;
            Anime.Episode e=new Anime.Episode();
            e.id="he-"+n;
            e.number=n;
            e.lazy="henta";
            e.variants.add(0,new Anime.Variant("Суб","Tsuyu",file));
            a.episodeList.add(e);
        }
    }

    private String hentaistreamSeriesFile(String seriesUrl)throws Exception{
        String html=api.request(seriesUrl,"GET",null,false,heHeaders());
        String ep=firstEpisodeUrl(html);
        return ep==null?null:hentaistreamFile(ep);
    }

    private static String firstEpisodeUrl(String html){
        Pattern p=Pattern.compile("https://tube\\.hentaistream\\.com/[a-z0-9-]+-episode-(\\d+)");
        Matcher m=p.matcher(html);
        String best=null;
        int bestN=9999;
        while(m.find()){
            int n=Integer.parseInt(m.group(1));
            if(n<bestN){bestN=n;best=m.group(0);}
        }
        return best;
    }

    private List<Row> rowsHentaistreamSearch(String q)throws Exception{
        List<Row> out=new ArrayList<>();
        if(q==null||q.trim().length()<3)return out;
        String u="https://tube.hentaistream.com/?s="+java.net.URLEncoder.encode(q.trim(),"UTF-8");
        String html=api.request(u,"GET",null,false,heHeaders());
        Pattern p=Pattern.compile("<a[^>]+href=\"https://tube\\.hentaistream\\.com/hentaidvd/([a-z0-9-]+)\"[^>]*>([^<]{4,200})</a>");
        Matcher m=p.matcher(html);
        LinkedHashMap<String,Row> map=new LinkedHashMap<>();
        while(m.find()){
            String slug=m.group(1);
            String title=unesc(m.group(2)).trim();
            if(title.length()<4||map.containsKey(slug))continue;
            String poster=hentaCoverFallback(title,title,slug);
            map.put(slug,new Row("he",numId(slug),title,title,poster,"https://tube.hentaistream.com/hentaidvd/"+slug,slug,0));
        }
        for(Row r:map.values()){
            out.add(r);
            if(out.size()>=8)break;
        }
        if(!out.isEmpty())return out;
        Pattern ep=Pattern.compile("https://tube\\.hentaistream\\.com/([a-z0-9-]+)-episode-\\d+");
        Matcher m2=ep.matcher(html);
        if(m2.find()){
            String epSlug=m2.group(1);
            String title=slugTitle(m2.group(0));
            String poster=hentaCoverFallback(title,title,epSlug);
            out.add(new Row("he",numId("he-"+epSlug),title,"",poster,m2.group(0),m2.group(0),0));
        }
        return out;
    }

    static String hentaCoverFallback(String title,String orig,String slug){
        String n=norm((title!=null?title:"")+" "+(orig!=null?orig:"")+" "+(slug!=null?slug:""));
        if(n.contains("лимонн")||n.contains("limonny")||n.contains("ramune")||n.contains("девичий лимонад")||n.contains("lemon girl")||n.contains("lemon girls")){
            return "https://image.tmdb.org/t/p/w500/vNaxbvlAKvICuhgLk7y2RNlHL6L.jpg";
        }
        return "";
    }

    private static String hentaQueryAlias(String q){
        String n=norm(q);
        if(n.contains("лимонн")||n.contains("limonny")||n.contains("lemon girl")||n.contains("lemon girls")||n.contains("shoujo ramune")||n.contains("shoujo-ramune")||n.contains("девичий лимонад"))return "shoujo ramune";
        return null;
    }

    private static final class SiteRows{
        final String site;
        final List<Row> rows;
        SiteRows(String site,List<Row> rows){this.site=site;this.rows=rows;}
    }

    private Anime.Page catalogBrowsed(int page)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(4);
        CompletionService<SiteRows> done=new ExecutorCompletionService<>(pool);
        for(String site:SITES){
            final String s=site;
            done.submit(()->new SiteRows(s,rowsFor(s,page)));
        }
        LinkedHashMap<String,List<Row>> bySite=new LinkedHashMap<>();
        for(String site:SITES)bySite.put(site,new ArrayList<Row>());
        long deadline=System.currentTimeMillis()+14000;
        try{
            for(int i=0;i<SITES.length;i++){
                long left=deadline-System.currentTimeMillis();
                if(left<=0)break;
                Future<SiteRows> f=done.poll(left,TimeUnit.MILLISECONDS);
                if(f==null)break;
                try{
                    SiteRows sr=f.get();
                    if(sr!=null&&bySite.containsKey(sr.site)&&sr.rows!=null)bySite.get(sr.site).addAll(sr.rows);
                }catch(Exception ignored){}
            }
        }finally{pool.shutdownNow();}
        LinkedHashMap<String,Row> map=new LinkedHashMap<>();
        boolean more=false;
        for(String site:SITES){
            for(Row r:bySite.get(site)){
                if(r==null||r.title.length()<6)continue;
                String k=norm(r.title);
                if(k.length()<6||map.containsKey(k))continue;
                map.put(k,r);
                more=true;
                if(map.size()>=36)break;
            }
            if(map.size()>=36)break;
        }
        if(map.isEmpty())throw new IOException("Каталог сейчас не отвечает");
        return verifiedPage(new ArrayList<>(map.values()),more);
    }

    private Anime.Page catalogSearched(String q,int page){
        if(page>1)return emptyPage("Дальше ничего нет");
        LinkedHashMap<String,Row> map=new LinkedHashMap<>();
        ArrayList<String[]> titles=new ArrayList<>();
        try{titles=shikiTitles(q);}catch(Exception ignored){}
        ExecutorService pool=Executors.newFixedThreadPool(6);
        CompletionService<List<Row>> done=new ExecutorCompletionService<>(pool);
        boolean scanSites=!titles.isEmpty();
        if(scanSites)for(String site:new String[]{"hs","ah","hk"}){final String s=site;done.submit(()->{List<Row> out=new ArrayList<>();out.addAll(rowsFor(s,1));out.addAll(rowsFor(s,2));return out;});}
        done.submit(()->rowsPorncadoSearch(q));
        done.submit(()->rowsHanimeSearch(q));
        final String heq=hentaQueryAlias(q)!=null?hentaQueryAlias(q):q;
        done.submit(()->rowsHentaistreamSearch(heq));
        done.submit(()->rowsAnihentaiSearch(q));
        int tasks=(scanSites?3:0)+4;
        long deadline=System.currentTimeMillis()+16000;
        try{
            for(int i=0;i<tasks;i++){
                long left=deadline-System.currentTimeMillis();
                if(left<=0)break;
                Future<List<Row>> f=null;
                try{f=done.poll(left,TimeUnit.MILLISECONDS);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                if(f==null)break;
                try{
                    for(Row r:f.get()){
                        if(r==null||r.title.length()<6)continue;
                        if(scanSites&&!r.site.equals("hn")&&!r.site.equals("he")&&!r.site.equals("an")&&!matches(r,titles))continue;
                        String k=norm(r.title);
                        if(k.length()<6||map.containsKey(k))continue;
                        map.put(k,r);
                        if(map.size()>=20)break;
                    }
                }catch(Exception ignored){}
            }
        }finally{pool.shutdownNow();}
        if(map.isEmpty())return emptyPage("Рабочего хентай по запросу не найдено");
        return verifiedPage(new ArrayList<>(map.values()),false);
    }

    private static Anime.Page emptyPage(String note){
        Anime.Page p=new Anime.Page();
        p.page=1;
        p.note=note;
        return p;
    }

    private boolean matches(Row r,List<String[]> titles){
        String rn=norm(r.title),on=norm(r.orig);
        for(String[] t:titles){
            String a=norm(t[0]),b=norm(t[1]);
            if(a.length()>=8&&(rn.equals(a)||rn.contains(a)||a.contains(rn)))return true;
            if(b.length()>=8&&(on.length()>=8&&on.contains(b)||b.contains(on)||on.equals(b)))return true;
        }
        return false;
    }

    private ArrayList<String[]> shikiTitles(String q)throws Exception{
        ArrayList<String[]> out=new ArrayList<>();
        JSONObject data=api.hentaShiki("{animes(search:"+JSONObject.quote(q)+",limit:20,censored:false){"+FIELDS+"}}");
        JSONArray rows=data==null?null:data.optJSONArray("animes");
        if(rows==null)return out;
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);
            if(r==null)continue;
            String rating=r.optString("rating","").toUpperCase(Locale.ROOT);
            if(!rating.equals("RX")&&!rating.equals("R_PLUS")&&!rating.equals("R"))continue;
            out.add(new String[]{r.optString("russian",r.optString("name","")),r.optString("english",r.optString("name",""))});
            if(out.size()>=12)break;
        }
        return out;
    }

    private List<Row> rowsFor(String site,int page){
        List<Row> out=new ArrayList<>();
        try{
            if(site.equals("hn")){out.addAll(rowsHanime(page));return out;}
            if(site.equals("an")){out.addAll(rowsAnihentai(page));return out;}
            if(site.equals("he")){out.addAll(rowsHentaistream(page));return out;}
            if(site.equals("htv")){out.addAll(rowsHtv(page));return out;}
            String html=api.hentaText(pageUrl(site,Math.max(1,page)));
            if(site.equals("pc"))out.addAll(parsePorncado(html));
            else out.addAll(parseDle(html,site));
        }catch(Exception ignored){}
        return out;
    }

    private List<Row> rowsPorncadoSearch(String q){
        List<Row> out=new ArrayList<>();
        try{
            String u="https://porncado.com/?s="+java.net.URLEncoder.encode(q,"UTF-8");
            out.addAll(parsePorncado(api.hentaText(u)));
        }catch(Exception ignored){}
        return out;
    }

    private static final class HentaFile{
        final String url,label;
        HentaFile(String url,String label){this.url=url;this.label=label;}
    }

    private static String pageUrl(String site,int page){
        int n=Math.max(1,page);
        if(site.equals("hs"))return "https://hentasis1.top"+(n==1?"/":"/page/"+n);
        if(site.equals("ah"))return "https://allhentaii.fun/2d"+(n==1?"/":"/page/"+n);
        if(site.equals("hk"))return "https://v6.hentakli.org"+(n==1?"/":"/page/"+n);
        return "https://porncado.com/8-hentai"+(n==1?"/":"/page/"+n);
    }

    private static List<Row> parseDle(String html,String site){
        List<Row> out=new ArrayList<>();
        Pattern a=Pattern.compile("<a[^>]+href=\"(https?://[^\"]+/([1-9][0-9]{0,9})-[a-z0-9-]+\\.html)\"[^>]*>(.{0,700}?)</a>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        Matcher m=a.matcher(html);
        while(m.find()&&out.size()<40){
            String block=m.group(3);
            Matcher im=Pattern.compile("<img[^>]+>").matcher(block);
            if(!im.find())continue;
            String tag=im.group(0);
            String alt=unesc(attr(tag,"alt"));
            if(alt.length()<6)continue;
            String poster=attr(tag,"src");
            if(poster.isEmpty())poster=attr(tag,"data-src");
            if(poster.startsWith("/"))poster=host(site)+poster;
            out.add(new Row(site,m.group(2),rusPart(alt),origPart(alt),poster,m.group(1),"",yearOf(alt)));
        }
        return out;
    }

    private static List<Row> parsePorncado(String html){
        List<Row> out=new ArrayList<>();
        LinkedHashMap<String,Integer> seen=new LinkedHashMap<>();
        Matcher m=Pattern.compile("<a[^>]+href=\"(" + Pattern.quote(host("pc")) + "/([1-9][0-9]{0,9})-[a-z0-9-]+/)\"").matcher(html);
        while(m.find()&&out.size()<40){
            String sid=m.group(2);
            if(seen.containsKey(sid))continue;
            int start=m.end();
            String block=html.substring(start,Math.min(html.length(),start+1500));
            Matcher im=Pattern.compile("<img[^>]+>").matcher(block);
            String poster="",alt="";
            if(im.find()){String tag=im.group(0);poster=attr(tag,"src");alt=attr(tag,"alt");}
            String title=alt.isEmpty()?slugTitle(m.group(1)):unesc(alt);
            if(title.length()<5)continue;
            seen.put(sid,start);
            out.add(new Row("pc",sid,title,"",poster,m.group(1),"",0));
        }
        return out;
    }

    private Anime.Page verifiedPage(List<Row> rows,boolean more){
        Anime.Page out=new Anime.Page();
        out.page=1;
        int want=12;
        ExecutorService pool=Executors.newFixedThreadPool(8);
        CompletionService<Anime> done=new ExecutorCompletionService<>(pool);
        int submitted=0;
        for(Row r:rows){if(submitted>=32)break;done.submit(()->card(r));submitted++;}
        long deadline=System.currentTimeMillis()+28000;
        try{
            for(int i=0;i<submitted&&out.items.size()<want;i++){
                long left=deadline-System.currentTimeMillis();
                if(left<=0)break;
                Future<Anime> f=null;
                try{f=done.poll(left,TimeUnit.MILLISECONDS);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                if(f==null)break;
                try{
                    Anime a=f.get();
                    if(a!=null&&out.items.size()<want)out.items.add(a);
                }catch(Exception ignored){}
            }
        }finally{pool.shutdownNow();}
        out.more=more&&out.items.size()==want;
        if(out.items.isEmpty())out.note="Рабочих источников сейчас нет";
        return out;
    }

    private Anime card(Row r){
        try{
            String first=firstFileUrl(r);
            if(first==null||first.isEmpty())return null;
            try{
                Future<?> gate=PROBE_POOL.submit(()->{VideoResolver.probe(api,first);return null;});
                gate.get(6000,TimeUnit.MILLISECONDS);
            }catch(Exception ignored){}
            Anime a=new Anime();
            a.source="henta";
            a.id=digit(r.site)+r.sid;
            a.title=r.title;
            a.original=r.orig;
            a.poster=r.poster;
            if(a.poster==null||a.poster.isEmpty()){
                a.poster=hentaCoverFallback(a.title,a.original,r.ref.isEmpty()?r.url:r.ref);
            }
            a.hentaUrl=r.ref.isEmpty()?r.url:r.ref;
            a.year=r.year;
            a.type="Хентай";
            a.status="released";
            a.age="18+";
            return a;
        }catch(Exception e){return null;}
    }

    private String firstFileUrl(Row r)throws Exception{
        if(r.site.equals("an"))return anFile(r.url);
        if(r.site.equals("htv"))return htvFile(r.url);
        if(r.site.equals("he"))return hentaistreamFile(r.url);
        if(r.site.equals("hn")){
            JSONObject d=hanimeVideoJson(r.url);
            if(d==null)return null;
            StreamPick pick=new StreamPick();
            pickStream(d,pick);
            return pick.url;
        }
        if(r.site.equals("hs")){
            String html=api.hentaText(r.url);
            Matcher m=Pattern.compile("file:\"(https://svt[^\"]+\\.mp4)\"").matcher(html);
            return m.find()?m.group(1):null;
        }
        if(r.site.equals("ah")){
            String html=api.hentaText(r.url);
            Matcher m=Pattern.compile("file:\"(/pl/[^\"]+?)\"").matcher(html);
            if(!m.find())return null;
            JSONArray arr=new JSONArray(api.hentaText("https://allhentaii.fun"+m.group(1)));
            if(arr.length()==0)return null;
            return arr.getJSONObject(0).optString("file","");
        }
        if(r.site.equals("hk")){
            String html=api.hentaText(r.url);
            String vid=hentakliFirstVideoId(html);
            if(vid==null)return null;
            HentaFile hf=hentakliFile("https://v6.hentakli.org/video.php?id="+vid);
            return hf==null?null:hf.url;
        }
        String html=api.hentaText(r.url);
        Matcher m=Pattern.compile("<source[^>]+src=\"(https://xcdn[^\"]+)\"").matcher(html);
        return m.find()?m.group(1):null;
    }

    private static String hentakliFirstVideoId(String html){
        Matcher m=Pattern.compile("id\\\\?\"?:\\\\?\"?([1-9][0-9]{2,5})\\\\?\"?,\\\\?\"?parent").matcher(html);
        return m.find()?m.group(1):null;
    }

    private HentaFile hentakliFile(String videoPhpUrl)throws Exception{
        String html=api.hentaText(videoPhpUrl);
        Matcher m=Pattern.compile("(https?://)?//?(hencdn\\.top/video/[1-9][0-9]{0,9})").matcher(html);
        if(!m.find())return null;
        String u="https://"+m.group(2);
        if(!VideoResolver.probe(api,u).isEmpty())return new HentaFile(u,"Файл");
        String page=api.hentaText(u);
        HentaFile best=null;
        int bestRes=0;
        Matcher sm=Pattern.compile("<source[^>]+>").matcher(page);
        while(sm.find()){
            String tag=sm.group(0);
            String src=attr(tag,"src");
            if(src.isEmpty()||!(src.contains(".m3u8")||src.contains(".mp4")))continue;
            String res=attr(tag,"res");
            String label=attr(tag,"label");
            int rq=0;
            try{rq=res.isEmpty()?0:Integer.parseInt(res);}catch(Exception ignored){}
            String name=label.isEmpty()?(res.isEmpty()?"Файл":res+"p"):label.toLowerCase(Locale.ROOT).endsWith("p")?label:label+"p";
            if(best==null||rq>bestRes){best=new HentaFile(src,name);bestRes=rq;}
        }
        if(best!=null)return best;
        Matcher mm=Pattern.compile("(https?://[^\"]+\\.(?:mp4|m3u8)[^\"]*)").matcher(page);        return mm.find()?new HentaFile(mm.group(1),"Файл"):null;
    }

    public Anime quickDetails(Anime base)throws Exception{
        Anime a=Anime.from(base.json());
        try{
            JSONObject best=shikiSearch(norm(a.title));
            if(best==null)best=shikiSearch(norm(a.original));
            if(best!=null)mergeShiki(a,best);
        }catch(Exception ignored){}
        if(a.type.isEmpty())a.type="Хентай";
        if(a.age.isEmpty())a.age="18+";
        if(a.poster==null||a.poster.isEmpty()){
            a.poster=hentaCoverFallback(a.title,a.original,a.hentaUrl);
        }
        return a;
    }

    private JSONObject shikiSearch(String q)throws Exception{
        if(q.isEmpty())return null;
        JSONObject data=api.hentaShiki("{animes(search:"+JSONObject.quote(q)+",limit:15,censored:false){"+FIELDS+"}}");
        JSONArray rows=data==null?null:data.optJSONArray("animes");
        if(rows==null)return null;
        JSONObject best=null;
        double bestScore=-1;
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);
            if(r==null)continue;
            String rating=r.optString("rating","").toUpperCase(Locale.ROOT);
            if(!rating.equals("RX")&&!rating.equals("R_PLUS"))continue;
            double sc=r.optDouble("score",0);
            if(best==null||sc>bestScore){best=r;bestScore=sc;}
        }
        return best;
    }

    private void mergeShiki(Anime a,JSONObject j){
        try{
            a.malId=j.optInt("malId",a.malId);
            String kind=j.optString("kind","");
            if(!kind.isEmpty())a.type=kind;
            if(!j.optString("russian","").isEmpty())a.title=j.optString("russian");
            if(!j.optString("english","").isEmpty())a.original=j.optString("english");
            a.score=j.optDouble("score",a.score);
            a.episodes=j.optInt("episodes",a.episodes);
            a.episodesAired=j.optInt("episodesAired",a.episodesAired);
            a.status=j.optString("status",a.status);
            JSONObject ao=j.optJSONObject("airedOn");
            if(ao!=null&&ao.optInt("year",0)>0)a.year=ao.optInt("year");
            JSONObject p=j.optJSONObject("poster");
            if(p!=null){
                String url=p.optString("mainUrl","");
                if(url.isEmpty())url=p.optString("originalUrl","");
                if(!url.isEmpty())a.poster=url;
            }
            JSONArray gs=j.optJSONArray("genres");
            if(gs!=null){
                a.genres.clear();
                for(int i=0;i<gs.length();i++){
                    JSONObject g=gs.optJSONObject(i);
                    if(g!=null){String n=g.optString("russian",g.optString("name",""));if(!n.isEmpty())a.genres.add(n);}
                }
            }
            String d=ApiRepository.plain(j.optString("descriptionHtml",""));
            if(d.length()>60)a.description=d;
        }catch(Exception ignored){}
    }

    public Anime details(Anime base,boolean episodes)throws Exception{
        Anime a=Anime.from(base.json());
        if(!episodes)return a;
        if(a.id.length()<2)return a;
        String site=siteOf(a.id.charAt(0));
        if(site.equals("hn")){
            try{fillHanime(a,a.hentaUrl);}catch(Exception ignored){}
            a.episodes=Math.max(a.episodes,a.episodeList.size());
            a.episodesAired=Math.max(a.episodesAired,a.episodeList.size());
            return a;
        }
        if(site.equals("an")){
            try{fillAnihentai(a);}catch(Exception ignored){}
            a.episodes=Math.max(a.episodes,a.episodeList.size());
            a.episodesAired=Math.max(a.episodesAired,a.episodeList.size());
            return a;
        }
        if(site.equals("he")){
            try{fillHentaistream(a);}catch(Exception ignored){}
            a.episodes=Math.max(a.episodes,a.episodeList.size());
            a.episodesAired=Math.max(a.episodesAired,a.episodeList.size());
            return a;
        }
        if(site.equals("htv")){
            try{fillHtv(a);}catch(Exception ignored){}
            a.episodes=Math.max(a.episodes,a.episodeList.size());
            a.episodesAired=Math.max(a.episodesAired,a.episodeList.size());
            return a;
        }
        String html;
        try{html=api.hentaText(a.hentaUrl);}catch(Exception e){return a;}
        try{
            if(site.equals("hs"))fillHentasis(a,html);
            else if(site.equals("ah"))fillAllhentaii(a,html);
            else if(site.equals("hk"))fillHentakli(a,html);
            else fillPorncado(a,html);
        }catch(Exception ignored){}
        a.episodes=Math.max(a.episodes,a.episodeList.size());
        a.episodesAired=Math.max(a.episodesAired,a.episodeList.size());
        if(!a.episodeList.isEmpty()){
            Anime.Episode e0=a.episodeList.get(0);
            String u=e0.variants.isEmpty()?"":e0.variants.get(0).url;
            if(u.isEmpty()&&e0.streams.size()>0)u=e0.streams.firstEntry().getValue();
            if(u.isEmpty())a.episodeList.clear();
            else{
                final String checked=u;
                try{
                    Future<?> gate=PROBE_POOL.submit(()->{VideoResolver.probe(api,checked);return null;});
                    gate.get(6000,TimeUnit.MILLISECONDS);
                }catch(Exception ignored){}
            }
        }
        return a;
    }

    private void fillHentasis(Anime a,String html){
        Pattern p=Pattern.compile("file:\"(https://svt[^\"]+\\.mp4)\",title:\"([^\"]*)\"");
        LinkedHashMap<Integer,Anime.Episode> map=new LinkedHashMap<>();
        Matcher m=p.matcher(html);
        while(m.find()){
            String url=m.group(1);
            Matcher nm=Pattern.compile("-(\\d{2,3})_(rus|sub)_").matcher(url);
            if(!nm.find())continue;
            int n=Integer.parseInt(nm.group(1));
            Anime.Episode e=map.get(n);
            if(e==null){
                e=new Anime.Episode();
                e.id=String.valueOf(n);
                e.number=n;
                e.lazy="henta";
                map.put(n,e);
            }
            if(nm.group(2).equals("rus"))e.variants.add(0,new Anime.Variant("Озвучка","Tsuyu",url));
            else e.variants.add(new Anime.Variant("Суб","Tsuyu",url));
        }
        for(Anime.Episode e:map.values())a.episodeList.add(e);
    }

    private void fillAllhentaii(Anime a,String html)throws Exception{
        Matcher m=Pattern.compile("file:\"(/pl/[^\"]+?)\"").matcher(html);
        if(!m.find())return;
        JSONArray arr=new JSONArray(api.hentaText("https://allhentaii.fun"+m.group(1)));
        for(int i=0;i<arr.length();i++){
            JSONObject o=arr.optJSONObject(i);
            if(o==null)continue;
            String u=o.optString("file","");
            if(u.isEmpty())continue;
            Anime.Episode e=new Anime.Episode();
            e.id=String.valueOf(i+1);
            e.number=i+1;
            e.lazy="henta";
            e.name=o.optString("title","");
            e.variants.add(new Anime.Variant("Файл","Tsuyu",u));
            a.episodeList.add(e);
        }
    }

    private void fillHentakli(Anime a,String html)throws Exception{
        Matcher m=Pattern.compile("id\\\\?\"?:\\\\?\"?([1-9][0-9]{2,5})").matcher(html);;
        ArrayList<String[]> eps=new ArrayList<>();
        while(m.find()&&eps.size()<12)eps.add(new String[]{m.group(1),""});
        if(eps.isEmpty())return;
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(4,Math.max(1,eps.size())));
        CompletionService<HentaFile> done=new ExecutorCompletionService<>(pool);
        for(String[] e:eps)done.submit(()->{try{return hentakliFile("https://v6.hentakli.org/video.php?id="+e[0]);}catch(Exception ex){return null;}});
        long deadline=System.currentTimeMillis()+20000;
        try{
            for(int i=0;i<eps.size();i++){
                long left=deadline-System.currentTimeMillis();
                if(left<=0)break;
                Future<HentaFile> f=done.poll(left,TimeUnit.MILLISECONDS);
                if(f==null)break;
                String url=null;
                String label="Файл";
                try{HentaFile hf=f.get();if(hf!=null){url=hf.url;label=hf.label;}}catch(Exception ignored){}
                if(url==null||url.isEmpty())continue;
                Anime.Episode e=new Anime.Episode();
                e.id=eps.get(i)[0];
                e.number=i+1;
                e.lazy="henta";
                e.name=eps.get(i)[1];
                e.variants.add(new Anime.Variant(label,"Tsuyu",url));
                a.episodeList.add(e);
            }
        }finally{pool.shutdownNow();}
    }

    private void fillPorncado(Anime a,String html){
        ArrayList<Anime.Variant> vs=new ArrayList<>();
        Matcher m=Pattern.compile("<source[^>]*xcdn[^>]*>").matcher(html);
        while(m.find()&&vs.size()<4){
            String tag=m.group(0);
            String u=attr(tag,"src");
            String t=attr(tag,"title");
            if(u.isEmpty())continue;
            String name=t.equalsIgnoreCase("high")||t.equalsIgnoreCase("hi")?"Высокое":(t.equalsIgnoreCase("low")||t.equalsIgnoreCase("lo")?"Низкое":"Файл");
            vs.add(new Anime.Variant(name,"Tsuyu",u));
        }
        if(vs.isEmpty())return;
        Anime.Episode e=new Anime.Episode();
        e.id="1";
        e.number=1;
        e.lazy="henta";
        e.variants.addAll(vs);
        a.episodeList.add(e);
    }

    private static String attr(String tag,String name){
        Matcher m=Pattern.compile(name+"=\"([^\"]*)\"").matcher(tag);
        return m.find()?m.group(1):"";
    }

    private static String norm(String s){
        if(s==null)return "";
        s=s.toLowerCase(Locale.ROOT);
        s=s.replaceAll("[^\\p{L}\\p{N} ]+"," ");
        s=s.replaceAll("\\s+"," ").trim();
        return s;
    }

    private static String rusPart(String alt){
        if(alt==null)return "";
        String s=alt.trim();
        int i=s.indexOf(" / ");
        if(i>0)s=s.substring(0,i);
        s=s.replaceAll("\\s*\\(\\d{4}г?\\.[^)]*\\)\\s*$","");
        return s.trim();
    }

    private static String origPart(String alt){
        if(alt==null)return "";
        String s=alt.trim();
        int i=s.indexOf(" / ");
        if(i<0)return "";
        s=s.substring(i+3);
        s=s.replaceAll("\\s*\\(\\d{4}г?\\.[^)]*\\)\\s*$","");
        return s.trim();
    }

    private static int yearOf(String alt){
        Matcher m=Pattern.compile("\\((\\d{4})г?\\.").matcher(alt==null?"":alt);
        if(!m.find())return 0;
        int y=Integer.parseInt(m.group(1));
        return y>=1980&&y<=2030?y:0;
    }

    private static String slugTitle(String url){
        String s=url.replaceAll(".*/","").replaceAll("/$","").replace("-"," ");
        s=s.replaceAll("\\s+"," ").trim();
        return s.isEmpty()?"":Character.toUpperCase(s.charAt(0))+s.substring(1);
    }

    private static String unesc(String s){
        if(s==null)return "";
        return s.replace("&#8211;","\u2014").replace("&#8212;","\u2014").replace("&#8217;","\u2019").replace("&#8220;","\u00ab").replace("&#8221;","\u00bb").replace("&#160;"," ").replace("&amp;","&").replace("&quot;",String.valueOf((char)34)).replace("&lt;","<").replace("&gt;",">").trim();
    }
    private static JSONArray toJson(Anime.Page p){
        JSONArray arr=new JSONArray();
        for(Anime a:p.items)arr.put(a.json());
        return arr;
    }

    private static Anime.Page fromJson(JSONArray arr){
        Anime.Page p=new Anime.Page();
        p.page=1;
        p.more=true;
        for(int i=0;i<arr.length();i++){
            JSONObject o=arr.optJSONObject(i);
            if(o!=null)p.items.add(Anime.from(o));
        }
        return p;
    }
}
