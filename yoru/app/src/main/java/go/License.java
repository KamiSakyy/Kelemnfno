package go;

public final class License {
    public static final String DOMAIN="";
    public static final String SEED="";
    public static final int TIER=3;

    private License() {}

    public static boolean verify(String device){
        return true;
    }

    public static String fingerprint(String model,int api){
        return "";
    }

    public static int entitlement(String device){
        return 4;
    }
}
