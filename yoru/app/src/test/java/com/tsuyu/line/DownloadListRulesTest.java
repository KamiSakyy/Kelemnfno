package com.tsuyu.line;

import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadListRulesTest {
    @Test public void waitingPlanHasItsOwnCard(){assertTrue(DownloadListRules.showPlan(false,false));}
    @Test public void acceptedDownloadDoesNotDuplicatePlan(){assertFalse(DownloadListRules.showPlan(false,true));}
    @Test public void transferredPlanIsNotShownAgainAfterFileDeletion(){assertFalse(DownloadListRules.showPlan(true,false));}
}
