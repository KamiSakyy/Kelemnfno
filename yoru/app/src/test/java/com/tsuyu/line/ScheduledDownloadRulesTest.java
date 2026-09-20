package com.tsuyu.line;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScheduledDownloadRulesTest {
    @Test public void exactEpisodeOnly() {
        assertFalse(ScheduledDownloadRules.accepts(9,720,8,720,false));
        assertFalse(ScheduledDownloadRules.accepts(9,720,10,720,false));
        assertTrue(ScheduledDownloadRules.accepts(9.5,720,9.5,720,false));
    }
    @Test public void exactQualityWithoutDowngrade() {
        assertFalse(ScheduledDownloadRules.accepts(9,1080,9,720,false));
        assertFalse(ScheduledDownloadRules.accepts(9,720,9,1080,false));
        assertTrue(ScheduledDownloadRules.accepts(9,1080,9,1080,false));
        assertTrue(ScheduledDownloadRules.accepts(9,QualityPlus.BEST,9,2160,false));
    }
    @Test public void futureAndInvalidEpisodesCannotDownload() {
        assertFalse(ScheduledDownloadRules.accepts(9,720,9,720,true));
        assertFalse(ScheduledDownloadRules.accepts(Double.NaN,720,9,720,false));
        assertFalse(ScheduledDownloadRules.accepts(9,720,Double.POSITIVE_INFINITY,720,false));
        assertFalse(ScheduledDownloadRules.accepts(0,720,0,720,false));
    }
    @Test public void selectedVoiceIsNotReplacedByFallback() {
        assertFalse(ScheduledDownloadRules.voiceAllowed("AniDUB","AnimeVost"));
        assertFalse(ScheduledDownloadRules.voiceAllowed("AniStar","AniStar & DEEP"));
        assertFalse(ScheduledDownloadRules.voiceAllowed("AniDUB",""));
        assertTrue(ScheduledDownloadRules.voiceAllowed("AniLibria.TV","AniLiberty"));
        assertTrue(ScheduledDownloadRules.voiceAllowed("","AnimeVost"));
    }
}
