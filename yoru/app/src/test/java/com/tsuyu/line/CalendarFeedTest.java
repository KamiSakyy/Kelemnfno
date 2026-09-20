package com.tsuyu.line;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class CalendarFeedTest {
    @Test public void favoritesCannotSuppressGeneralFallback() throws Exception {
        ArrayList<String> calls=new ArrayList<>();
        CalendarFeed.Result<String,String> rows=CalendarFeed.collect(
            m->{calls.add("general");throw new IOException();},
            m->{calls.add("fallback");m.put("public","non-favorite");},
            m->{calls.add("personal");m.put("favorite","favorite");});
        assertEquals(Arrays.asList("general","fallback","personal"),calls);
        assertEquals(2,rows.size());assertTrue(rows.generalAvailable);
    }
    @Test public void emptyFavoritesStillShowGeneralFeed() throws Exception {
        Map<String,String> rows=CalendarFeed.collect(m->m.put("public","event"),m->fail(),m->{});
        assertEquals("event",rows.get("public"));
    }
    @Test public void partialGeneralFailureGetsSupplemented() throws Exception {
        Map<String,String> rows=CalendarFeed.collect(m->{m.put("one","event");throw new IOException();},m->m.put("two","event"),m->{});
        assertEquals(2,rows.size());
    }
    @Test public void personalFailureDoesNotEraseGeneralEvents() throws Exception {
        Map<String,String> rows=CalendarFeed.collect(m->m.put("public","event"),m->fail(),m->{throw new IOException();});
        assertEquals(1,rows.size());
    }
    @Test public void personalOnlyResultIsExplicitlyMarked() throws Exception {
        CalendarFeed.Result<String,String> rows=CalendarFeed.collect(m->{throw new IOException();},m->{throw new IOException();},m->m.put("favorite","event"));
        assertFalse(rows.generalAvailable);assertEquals(1,rows.size());
    }
    @Test public void allEventsAreVisibleNotFirstFive() {
        assertEquals(0,CalendarFeed.displayCount(0));
        assertEquals(120,CalendarFeed.displayCount(120));
        assertEquals(550,CalendarFeed.displayCount(550));
    }
    @Test(expected=IOException.class) public void totalFailureIsNotAnEmptySuccess() throws Exception {
        CalendarFeed.collect(m->{throw new IOException();},m->{throw new IOException();},m->{});
    }
}
