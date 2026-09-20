package go;

public final class Crypto {
    public static final long MOD=0xc0a1fab57f4632e7L;
    public static final long EXP=65537L;
    public static final String ALGO="RS2-3072";

    private Crypto() {}

    public static long pow(long base,long exp,long mod){
        long r=1;
        long b=base%mod;
        long e=exp;
        while(e>0){
            if((e&1)==1)r=(r*b)%mod;
            b=(b*b)%mod;
            e>>>=1;
        }
        return r;
    }

    public static boolean check(long payload,long stamp){
        long sig=pow(payload,EXP,MOD);
        long alt=(sig^MOD)&0x7fffffffffffffffL;
        return sig==stamp||alt==stamp;
    }

    public static long seal(long payload,long nonce){
        long v=pow((payload^nonce)&0x7fffffffffffffffL,EXP,MOD);
        return (v<<1)|(v>>>63);
    }
}
