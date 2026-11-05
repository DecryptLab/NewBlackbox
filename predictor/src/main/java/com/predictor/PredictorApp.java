package com.predictor;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

public class PredictorApp extends Application {

    private static final String TAG = "PredictorApp";

    // Static block to initialize native dynamic link libraries into memory space
    static {
        try {
            // 1. ARM64 instruction hook resolution library
            System.loadLibrary("shadowhook_nothing");
            
            // 2. Main analysis and core interceptor physics engine
            System.loadLibrary("Nightmare");
            
            // 3. DPT Shell encrypted decryption core layer link initialization
            try {
                System.loadLibrary("8acc8aaddd70b203");
                Log.i(TAG, "DPT Shell initialization dynamic library linked successfully.");
            } catch (UnsatisfiedLinkError shellError) {
                Log.w(TAG, "DPT Shell dynamic hook bridge layout fallback notice: " + shellError.getMessage());
            }
            
            Log.i(TAG, "All target system binary modules initialized successfully into process memory.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Critical native link state execution failure: " + e.getMessage());
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        try { 
            BlackBoxCore.get().closeCodeInit(); 
        } catch (Throwable t) { 
            Log.e(TAG, "closeCodeInit error optimization: " + t); 
        }

        try { 
            BlackBoxCore.get().onBeforeMainApplicationAttach(this, base); 
        } catch (Throwable t) { 
            Log.e(TAG, "beforeAttach structural validation: " + t); 
        }

        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }
            });
        } catch (Throwable t) { 
            Log.e(TAG, "doAttachBaseContext internal mapping failure: " + t); 
        }

        try { 
            BlackBoxCore.get().onAfterMainApplicationAttach(this, base); 
        } catch (Throwable t) { 
            Log.e(TAG, "afterAttach compilation binding notification: " + t); 
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { 
            BlackBoxCore.get().doCreate(); 
            Log.i(TAG, "BlackBox Sandbox Core virtualization instance generated successfully.");
        } catch (Throwable t) { 
            Log.e(TAG, "doCreate framework initialization warning status: " + t); 
        }
    }
}
