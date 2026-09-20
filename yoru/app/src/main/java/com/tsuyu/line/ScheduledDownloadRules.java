package com.tsuyu.line;

final class ScheduledDownloadRules {
    private ScheduledDownloadRules() {}
    static boolean accepts(double number,int quality,double actualNumber,int actualQuality,boolean future) {
        return Double.isFinite(number) && number>0 && Double.isFinite(actualNumber) && !future
                && Math.abs(actualNumber-number)<=0.001 && (quality==QualityPlus.BEST||quality==actualQuality);
    }
    static boolean voiceAllowed(String wanted,String actual) {
        return wanted==null||wanted.isEmpty()||(!ApiRepository.voiceKey(wanted).isEmpty()&&ApiRepository.voiceKey(wanted).equals(ApiRepository.voiceKey(actual)));
    }
    static boolean matches(double number,String voice,int quality,ApiRepository.DownloadOption option) {
        if(option==null||option.source==null||option.episode==null||option.episode.future)return false;
        if(!accepts(number,quality,option.episode.number,option.quality,option.episode.future))return false;
        if(!voiceAllowed(voice,option.voice==null||option.voice.isEmpty()?option.episode.name:option.voice))return false;
        String url=ApiRepository.safeUrl(option.episode.streams.get(option.quality));
        return !url.isEmpty()&&VideoResolver.downloadable(url);
    }
}
