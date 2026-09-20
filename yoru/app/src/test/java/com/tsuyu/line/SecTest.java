package com.tsuyu.line;

import org.junit.Assert;
import org.junit.Test;
import java.nio.charset.StandardCharsets;

public class SecTest {
    @Test
    public void testDecryption() {
        String test = "https://graphql.anilist.co";
        String s = Sec.s("3c07010906694a4c1517153b0d085e1e53583d1f1c0a017d060c");
        Assert.assertEquals(test, s);
        String s2 = Sec.s("3c07010906-694a4c151715-3b0d085e1e-53583d1f1c0a017d060c");
        Assert.assertEquals(test, s2);
        Assert.assertTrue(Sec.checkSecurity());
    }

    @Test
    public void testEmpty() {
        Assert.assertEquals("", Sec.s(""));
        Assert.assertEquals("", Sec.s(null));
        Assert.assertEquals(0, Sec.decryptData(null).length);
        Assert.assertEquals(0, Sec.decryptData(new byte[0]).length);
    }
}
