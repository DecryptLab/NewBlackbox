package com.predictor;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

public class PredictorApp extends Application {

    private static final String TAG = "PredictorApp";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        try { BlackBoxCore.get().closeCodeInit(); }
        catch (Throwable t) { Log.e(TAG, "closeCodeInit " + t); }

        try { BlackBoxCore.get().onBeforeMainApplicationAttach(this, base); }
        catch (Throwable t) { Log.e(TAG, "beforeAttach " + t); }

        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }
            });
        } catch (Throwable t) { Log.e(TAG, "doAttachBaseContext " + t); }

        try { BlackBoxCore.get().onAfterMainApplicationAttach(this, base); }
        catch (Throwable t) { Log.e(TAG, "afterAttach " + t); }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { BlackBoxCore.get().doCreate(); }
        catch (Throwable t) { Log.e(TAG, "doCreate " + t); }
    }
}
