package com.finmesh.app;

import android.app.Application;
import android.util.Log;

/**
 * FinMeshApp.java — Application class
 * Declared in AndroidManifest as android:name=".FinMeshApp"
 * Used to initialize app-wide singletons at startup.
 */
public class FinMeshApp extends Application {

    private static final String TAG = "FinMeshApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "FinMesh Application started.");

        // Initialize the wallet singleton at app launch
        WalletManager.getInstance(this);
    }
}
