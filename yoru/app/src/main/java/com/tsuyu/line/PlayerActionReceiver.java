package com.tsuyu.line;

import android.content.*;

public final class PlayerActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(intent!=null)PlayerActivity.pipAction(intent.getAction());}
}
