package com.tsuyu.line;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import java.io.IOException;

public final class NativeSources {
    private final ApiRepository api;
    public NativeSources(ApiRepository repository){api=repository;}
    private static String absolute(String base,String value){try{if(value==null||value.trim().isEmpty())return "";value=value.trim().replace(" ","%20").replace("&amp;","&");if(value.startsWith("//"))value="https:"+value;return ApiRepository.safeUrl(new URL(new URL(base),value).toString());}catch(Exception e){return "";}}
    public Anime.Page catalog(String source,String search,int page)throws Exception{
        if(!source.equals("animedia"))throw new IOException("Неизвестный маршрут");String base="https://amd.online";String html;
        if(search==null||search.trim().isEmpty()){html=api.document(base+(page==1?"/":"/page/"+page+"/"));}
        else{String body="do=search&subaction=search&full_search=0&result_from=1&search_start="+page+"&story="+URLEncoder.encode(search,"UTF-8");html=api.formPage(base+"/index.php?do=search",body);}
        Document doc=Jsoup.parse(html,base);Anime.Page result=new Anime.Page();result.page=page;LinkedHashMap<String,Anime> items=new LinkedHashMap<>();
        for(Element box:doc.select(".grid-item,.poster,.short,.th-item")){Element a=box.selectFirst("a.poster__link[href],a[href*='.html']");if(a==null)continue;String url=absolute(base,a.attr("href"));Matcher m=Pattern.compile("/(\\d+)[^/]*\\.html").matcher(url);if(!m.find())continue;Element title=box.selectFirst(".poster__title,.th-title,.short-title");String text=title==null?a.text():title.text();if(text.trim().isEmpty())continue;Anime row=new Anime();row.source=source;row.id=m.group(1);row.alias=url;row.title=text.trim();row.type="Аниме";row.status="published";Element img=box.selectFirst("img[src],img[data-src]");if(img!=null)row.poster=absolute(base,img.hasAttr("src")?img.attr("src"):img.attr("data-src"));items.put(row.key(),api.remember(row));}
        result.items.addAll(items.values());for(Element a:doc.select(".navigation a[href],.pagination a[href],.nav-next a[href]")){String href=a.attr("href");Matcher p=Pattern.compile("/page/(\\d+)").matcher(href);if(p.find()&&Integer.parseInt(p.group(1))>page)result.more=true;if(a.attr("rel").equals("next"))result.more=true;}
        if(result.items.isEmpty()&&!doc.text().toLowerCase(Locale.ROOT).contains("найден"))throw new IOException("Страница временно изменилась Попробуйте позже");return result;
    }
    public Anime details(Anime base,boolean episodes)throws Exception{
        String host="https://amd.online";String url=base.alias.startsWith("https://")?base.alias:absolute(host,base.alias);if(url.isEmpty()||!new URL(url).getHost().equals(new URL(host).getHost()))throw new IOException("ÐÑÐºÑÐ¾Ð¹ÑÐµ ÐºÐ°ÑÑÐ¾ÑÐºÑ ÑÐµÑÐµÐ· Tsuyu");Document doc=Jsoup.parse(api.document(url),url);Anime a=Anime.from(base.json());a.alias=url;
        Element title=doc.selectFirst("h1");if(title!=null)a.title=title.text();Element description=doc.selectFirst(".page__text,.pmovie__description,.full-text,.fdesc");if(description!=null)a.description=description.text();Element picture=doc.selectFirst(".pmovie__poster img,.page__poster img,.poster__img img");if(picture!=null)a.poster=absolute(url,picture.attr("src"));String warnings=doc.select(".player-blocked,.alert-danger").text();if(warnings.contains("правообладател")){a.blocked=true;return a;}
        if(episodes){TreeMap<Double,Anime.Episode> rows=new TreeMap<>();for(Element e:doc.select("[data-vid][data-vlnk]")){double number;try{number=Double.parseDouble(e.attr("data-vid"));}catch(Exception bad){continue;}String vod=absolute(host,e.attr("data-vlnk"));if(number<1||vod.isEmpty()||!vod.contains("/vod/"))continue;if(rows.containsKey(number))continue;Anime.Episode ep=new Anime.Episode();ep.id=e.attr("data-vid");ep.number=number;ep.lazy="animedia";ep.resolverUrl=vod;rows.put(number,ep);}a.episodeList.addAll(rows.values());a.episodes=a.episodeList.size();if(a.episodes==0)throw new IOException("Пока нет доступных серий");}
        return api.remember(a);
    }
    public void loadEpisode(Anime.Episode ep)throws Exception{
        if(!ep.lazy.equals("animedia")||!ep.streams.isEmpty())return;String page=api.document(ep.resolverUrl);Matcher file=Pattern.compile("file\\s*:\\s*[\"']([^\"']+)[\"']").matcher(page);if(!file.find())throw new IOException("Видео пока недоступно");String url=absolute(ep.resolverUrl,file.group(1));if(url.isEmpty())throw new IOException("Видео пока недоступно");String manifest=api.document(url);if(!manifest.trim().startsWith("#EXTM3U"))throw new IOException("Просмотр пока не открыл видео");String[] lines=manifest.split("\\r?\\n");int height=0;for(String l:lines){l=l.trim();if(l.startsWith("#EXT-X-STREAM-INF")){Matcher m=Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(l);height=m.find()?Integer.parseInt(m.group(1)):0;}else if(!l.isEmpty()&&!l.startsWith("#")&&height>0){ep.streams.put(height,absolute(url,l));height=0;}}if(ep.streams.isEmpty())ep.streams.put(0,url);
    }
}
