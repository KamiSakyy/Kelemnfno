package com.tsuyu.line;

import java.util.LinkedHashMap;

final class CalendarFeed {
    interface Loader<K,V> { void load(LinkedHashMap<K,V> rows) throws Exception; }
    private CalendarFeed() {}

    static final class Result<K,V> extends LinkedHashMap<K,V> { boolean generalAvailable; }

    static <K,V> Result<K,V> collect(Loader<K,V> general,Loader<K,V> fallback,Loader<K,V> personal) throws Exception {
        Result<K,V> rows=new Result<>();
        Exception failure=null;
        try{general.load(rows);}catch(Exception e){TaskQueue.check();failure=e;}
        if(rows.isEmpty()||failure!=null)try{fallback.load(rows);}catch(Exception e){TaskQueue.check();failure=e;}
        rows.generalAvailable=!rows.isEmpty();
        try{personal.load(rows);}catch(Exception e){TaskQueue.check();if(failure==null)failure=e;}
        TaskQueue.check();
        if(rows.isEmpty()&&failure!=null)throw failure;
        return rows;
    }

    static int displayCount(int total){return Math.max(0,total);}
}
