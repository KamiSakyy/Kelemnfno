package com.tsuyu.line;

final class DownloadListRules {
    private DownloadListRules() {}
    static boolean showPlan(boolean transferred,boolean downloadPresent){return !transferred&&!downloadPresent;}
}
