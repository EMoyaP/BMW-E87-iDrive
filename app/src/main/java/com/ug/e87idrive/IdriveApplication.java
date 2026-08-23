package com.ug.e87idrive;

import android.app.Application;

public final class IdriveApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppSessionLog.initialize(this);
        SpeedPlayMediaReceiver.register(this);
    }
}
