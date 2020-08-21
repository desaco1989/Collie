package com.snail.collie;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.snail.collie.core.ActivityStack;
import com.snail.collie.core.CollieHandlerThread;
import com.snail.collie.debug.DebugHelper;
import com.snail.collie.fps.FpsTracker;
import com.snail.collie.fps.ITrackFpsListener;
import com.snail.collie.mem.MemoryLeakTrack;
import com.snail.collie.trafficstats.ITrackTrafficStatsListener;
import com.snail.collie.trafficstats.TrafficStatsTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class Collie {

    private static volatile Collie sInstance = null;
    private Handler mHandler;
    private ITrackFpsListener mITrackListener;
    private ITrackTrafficStatsListener mTrackTrafficStatsListener;
    private MemoryLeakTrack.ITrackMemoryLeakListener mITrackMemoryLeakListener;

    private List<CollieListener> mCollieListeners = new ArrayList<>();
    private HashSet<Application.ActivityLifecycleCallbacks> mActivityLifecycleCallbacks = new HashSet<>();

    private Collie() {
        mHandler = new Handler(CollieHandlerThread.getInstance().getHandlerThread().getLooper());
        mITrackListener = new ITrackFpsListener() {
            @Override
            public void onHandlerMessageCost(final long currentCostMils, final long currentDropFrame, final boolean isInFrameDraw, final long averageFps) {
                final long currentFps = currentCostMils == 0 ? 60 : Math.min(60, 1000 / currentCostMils);
//                Log.v("Collie", "实时帧率 " + currentFps + " 掉帧 " + currentDropFrame + " 1S平均帧率 " + averageFps + " 本次耗时 " + currentCostMils);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {

                        if (BuildConfig.DEBUG) {
                            if (currentDropFrame > 0)
                                DebugHelper.getInstance().update("实时fps " + currentFps +
                                        "\n 丢帧 " + currentDropFrame + " \n1s平均fps " + averageFps
                                        + " \n本次耗时 " + currentCostMils);
                        }

                        for (CollieListener collieListener : mCollieListeners) {
                            collieListener.onFpsTrack(ActivityStack.getInstance().getTopActivity(), currentFps, currentDropFrame, averageFps);
                        }
                    }

                });
            }
        };

        mTrackTrafficStatsListener = new ITrackTrafficStatsListener() {
            @Override
            public void onTrafficStats(String activityName, long value) {
                Log.v("Collie", "" + activityName + " 流量消耗 " + value * 1.0f / (1024 * 1024) + "M");
            }
        };

        mITrackMemoryLeakListener = new MemoryLeakTrack.ITrackMemoryLeakListener() {
            @Override
            public void onLeakActivity(String activity, int count) {
                Log.v("Collie", "内存泄露 " + activity + " 数量 " + count);
            }
        };
    }

    public static Collie getInstance() {
        if (sInstance == null) {
            synchronized (Collie.class) {
                if (sInstance == null) {
                    sInstance = new Collie();
                }
            }
        }
        return sInstance;
    }

    public void addActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callbacks) {
        mActivityLifecycleCallbacks.add(callbacks);
    }

    public void removeActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callbacks) {
        mActivityLifecycleCallbacks.remove(callbacks);
    }

    private Application.ActivityLifecycleCallbacks mActivityLifecycleCallback = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle bundle) {
            ActivityStack.getInstance().push(activity);
            for (Application.ActivityLifecycleCallbacks item : mActivityLifecycleCallbacks) {
                item.onActivityCreated(activity, bundle);
            }
        }

        @Override
        public void onActivityStarted(@NonNull final Activity activity) {
            for (Application.ActivityLifecycleCallbacks item : mActivityLifecycleCallbacks) {
                item.onActivityStarted(activity);
            }
            FpsTracker.getInstance().addTrackerListener(mITrackListener);
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            ActivityStack.getInstance().markResume();
            for (Application.ActivityLifecycleCallbacks item : mActivityLifecycleCallbacks) {
                item.onActivityResumed(activity);
            }
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            for (Application.ActivityLifecycleCallbacks item : mActivityLifecycleCallbacks) {
                item.onActivityPaused(activity);
            }
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            ActivityStack.getInstance().markStop();
            for (Application.ActivityLifecycleCallbacks item : mActivityLifecycleCallbacks) {
                item.onActivityStopped(activity);
            }
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) {
            for (Application.ActivityLifecycleCallbacks item : mActivityLifecycleCallbacks) {
                item.onActivitySaveInstanceState(activity, bundle);
            }
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            for (Application.ActivityLifecycleCallbacks item : mActivityLifecycleCallbacks) {
                item.onActivityDestroyed(activity);
            }
            ActivityStack.getInstance().pop(activity);
        }
    };

    public void init(@NonNull Application application,
                     final Config config,
                     final CollieListener listener) {
        application.registerActivityLifecycleCallbacks(mActivityLifecycleCallback);
        mCollieListeners.add(listener);

        if (config.userTrafficTrack) {
            TrafficStatsTracker.getInstance().addTackTrafficStatsListener(mTrackTrafficStatsListener);
        }
        if (config.userActivityLeak) {
            MemoryLeakTrack.getInstance().startTrack();
            MemoryLeakTrack.getInstance().addOnMemoryLeakListener(mITrackMemoryLeakListener);
        }
        if (config.userFpsTrack) {
            FpsTracker.getInstance().startTrack();
        }
        if (config.showDebugView) {
            DebugHelper.getInstance().startTrack();
        }
    }

    public void registerCollieListener(CollieListener listener) {
        mCollieListeners.add(listener);
    }

    public void unRegisterCollieListener(CollieListener listener) {
        mCollieListeners.remove(listener);
    }

    public void stop(@NonNull Application application) {
        application.unregisterActivityLifecycleCallbacks(mActivityLifecycleCallback);
        CollieHandlerThread.getInstance().getHandlerThread().quitSafely();
    }
}