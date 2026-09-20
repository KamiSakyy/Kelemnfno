package com.tsuyu.line;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class YoruGuard {
    private static final int[] F1={0x53,0x0e,0x45,0x6e,0xec,0x67,0x62,0xaa,0x37,0x1a,0x22,0xa2,0x2b,0xb5,0x8a,0x6d};
    private static final int[] F2={0x69,0x9d,0x78,0x40,0xe7,0xb4,0x1a,0xf5,0x9b,0xa8,0xe2,0xfe,0xa9,0xf3,0xc3,0xe7};
    private static final int[] K0={0x85,0xeb,0xca,0x6b,0xc2,0xb2,0xae,0x3d,0x9e,0x37,0x79,0xb9,0xff,0x51,0xaf,0xd7};
    private static final int[] X1={0x64,0x65,0x2e,0x72,0x6f,0x62,0x76,0x2e,0x61,0x6e,0x64,0x72,0x6f,0x69,0x64,0x2e,0x78,0x70,0x6f,0x73,0x65,0x64,0x2e,0x58,0x70,0x6f,0x73,0x65,0x64,0x42,0x72,0x69,0x64,0x67,0x65};
    private static final int[] X2={0x63,0x6f,0x6d,0x2e,0x73,0x61,0x75,0x72,0x69,0x6b,0x2e,0x73,0x75,0x62,0x73,0x74,0x72,0x61,0x74,0x65,0x2e,0x4d,0x53,0x43,0x6f,0x6e,0x66,0x69,0x67};
    private static final String[] N1={"frida","xposed","riru","lsposed","zygisk","magisk"};
    private static volatile int state;
    private static long lastProbe;

    private YoruGuard() {}

    static void arm(Context context){
        if(BuildConfig.DEBUG)return;
        Context app=context.getApplicationContext();
        YoruApp y=YoruApp.app();
        if(y==null)return;
        y.io.execute(()->{
            try{
                int s=runSignature(app,0x5389d613);
                if(s==0)state=1;
            }catch(Exception ignored){}
        });
    }

    static boolean blocked(){
        if(state==1)return true;
        long now=System.currentTimeMillis();
        if(now-lastProbe>10000L){
            lastProbe=now;
            try{
                int s=runProbe(0x27d4eb2f);
                if(s==1)state=1;
            }catch(Exception ignored){}
        }
        return state==1;
    }

    private static int route(int s,int k){
        int v=s^K0[k&15];
        v=(v<<5)|(v>>>27);
        v^=v>>>15;
        v+=K0[(v>>>13)&15]*31;
        return v;
    }

    private static int runSignature(Context context,int key){
        int idx=2;
        int out=1;
        int s=route(key,0);
        PackageManager pm=null;
        PackageInfo pi=null;
        Signature[] rows=null;
        for(int t=0;t<14;t++){
            s=route(s,idx);
            switch(idx){
                case 2:
                    idx=5;
                    break;
                case 5:
                    if(context==null){out=-1;idx=13;}
                    else{pm=context.getPackageManager();idx=7;}
                    break;
                case 7:
                    try{pi=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);}
                    catch(Exception e){pi=null;}
                    idx=9;
                    break;
                case 9:
                    if(out==1){
                        rows=pi==null?null:pi.signatures;
                        if(rows==null||rows.length==0)out=-1;
                        else out=match(rows);
                    }
                    idx=13;
                    break;
                case 13:
                    t=99;
                    break;
            }
        }
        return out;
    }

    private static int match(Signature[] rows){
        String need=expected();
        int out=0;
        int n=0;
        for(Signature row:rows){
            n++;
            if(row!=null&&need.equals(sha256(row.toByteArray())))out=1;
        }
        return out==1?1:(n>0?0:0);
    }

    private static int runProbe(int key){
        int idx=1;
        int bad=0;
        int s=route(key,7);
        for(int t=0;t<16;t++){
            s=route(s,idx);
            switch(idx){
                case 1:
                    idx=4;
                    break;
                case 4:
                    if(tracerPid()>0||android.os.Debug.isDebuggerConnected()||android.os.Debug.waitingForDebugger())bad=1;
                    idx=6;
                    break;
                case 6:
                    if(bad==0&&mapsHit())bad=1;
                    idx=8;
                    break;
                case 8:
                    if(bad==0&&hookPresent())bad=1;
                    idx=10;
                    break;
                case 10:
                    if(bad==0&&fridaPresent())bad=1;
                    idx=12;
                    break;
                case 12:
                    t=99;
                    break;
            }
        }
        return bad;
    }

    private static boolean fridaPresent(){
        try{
            if(new java.io.File("/data/local/tmp/frida-server").exists())return true;
        }catch(Exception ignored){}
        try(BufferedReader r=reader("/proc/net/tcp")){
            String line;
            while((line=r.readLine())!=null){
                if(line.contains(":69AA ") || line.contains(":69AA\t"))return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    private static int tracerPid(){
        try(BufferedReader r=reader("/proc/self/status")){
            String line;
            while((line=r.readLine())!=null){
                if(line.startsWith("TracerPid:"))return Integer.parseInt(line.substring(10).trim());
            }
        }catch(Exception ignored){}
        return 0;
    }

    private static boolean mapsHit(){
        try(BufferedReader r=reader("/proc/self/maps")){
            String line;
            while((line=r.readLine())!=null){
                String low=line.toLowerCase(Locale.ROOT);
                for(String n:N1){
                    if(low.contains(n))return true;
                }
            }
        }catch(Exception ignored){}
        return false;
    }

    private static boolean hookPresent(){
        for(int[] x:new int[][]{X1,X2}){
            StringBuilder b=new StringBuilder();
            for(int v:x)b.append((char)v);
            try{Class.forName(b.toString());return true;}catch(Exception ignored){}
        }
        return false;
    }

    private static BufferedReader reader(String path)throws Exception{
        return new BufferedReader(new InputStreamReader(new FileInputStream(path),StandardCharsets.UTF_8));
    }

    private static String expected(){
        StringBuilder b=new StringBuilder();
        append(b,F1);
        append(b,F2);
        return b.toString();
    }

    private static void append(StringBuilder b,int[] f){
        for(int v:f)b.append(String.format(Locale.US,"%02x",v));
    }

    private static String sha256(byte[] data){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] d=md.digest(data);
            StringBuilder b=new StringBuilder();
            for(byte x:d)b.append(String.format(Locale.US,"%02x",x));
            return b.toString();
        }catch(Exception e){
            return "";
        }
    }
}
