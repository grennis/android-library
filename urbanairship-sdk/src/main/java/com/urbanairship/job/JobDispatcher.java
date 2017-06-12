/* Copyright 2017 Urban Airship and Contributors */

package com.urbanairship.job;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.RestrictTo;
import android.support.annotation.VisibleForTesting;
import android.support.v4.content.WakefulBroadcastReceiver;

import com.google.android.gms.common.ConnectionResult;
import com.urbanairship.AirshipComponent;
import com.urbanairship.AirshipService;
import com.urbanairship.Logger;
import com.urbanairship.UAirship;
import com.urbanairship.google.PlayServicesUtils;
import com.urbanairship.util.UAStringUtil;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


/**
 * Dispatches jobs. When a job is dispatched with a delay or specifies that it requires network activity,
 * it will be scheduled using either the AlarmManager or GcmNetworkManager. When a job is finally performed,
 * it will call {@link com.urbanairship.AirshipComponent#onPerformJob(UAirship, Job)}
 * for the component the job specifies.
 *
 * @hide
 */
public class JobDispatcher {


    private static final long AIRSHIP_WAIT_TIME_MS = 10000; // 10 seconds.

    private final Context context;
    private static JobDispatcher instance;
    private Scheduler scheduler;
    private boolean isGcmScheduler = false;

    Executor executor = Executors.newSingleThreadExecutor();

    /**
     * Callback when a job is finished.
     */
    public interface Callback {

        /**
         * Called when a job is finished.
         *
         * @param job The job.
         * @param result The job's result.
         */
        void onFinish(Job job, @Job.JobResult int result);
    }


    /**
     * Gets the shared instance.
     *
     * @param context The application context.
     * @return The JobDispatcher.
     */
    public static JobDispatcher shared(@NonNull Context context) {
        if (instance == null) {
            synchronized (JobDispatcher.class) {
                if (instance == null) {
                    instance = new JobDispatcher(context);
                }
            }
        }

        return instance;
    }

    @VisibleForTesting
    JobDispatcher(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @VisibleForTesting
    JobDispatcher(@NonNull Context context, @NonNull Scheduler scheduler) {
        this.context = context.getApplicationContext();
        this.scheduler = scheduler;
    }

    /**
     * Dispatches a job to be performed immediately with a wakelock. The wakelock will
     * automatically be released once the job finishes. The job will not have a wakelock on
     * retries.
     *
     * @param job The job.
     * @return {@code true} if the job was able to be dispatched with a wakelock, otherwise {@code false}.
     */
    public boolean wakefulDispatch(@NonNull Job job) {
        if (getScheduler().requiresScheduling(context, job)) {
            Logger.error("JobDispatcher - Unable to wakefulDispatch with a job that requires scheduling.");
            return false;
        }

        Intent intent = AirshipService.createIntent(context, job);
        try {
            WakefulBroadcastReceiver.startWakefulService(context, intent);
            if (job.getTag() != null) {
                cancel(job.getTag());
            }
            return true;
        } catch (IllegalStateException e) {
            WakefulBroadcastReceiver.completeWakefulIntent(intent);
            return false;
        }
    }


    /**
     * Dispatches a job to be performed immediately.
     *
     * @param job The job.
     */
    public void dispatch(@NonNull Job job) {
        try {
            // Cancel any jobs with the same tag
            if (job.getTag() != null) {
                cancel(job.getTag());
            }

            if (getScheduler().requiresScheduling(context, job)) {
                getScheduler().schedule(context, job);
                return;
            }

            // Otherwise start the service directly
            try {
                context.startService(AirshipService.createIntent(context, job));
            } catch (IllegalStateException ex) {
                getScheduler().schedule(context, job);
            }
        } catch (SchedulerException e) {
            Logger.error("Scheduler failed to schedule job", e);

            if (isGcmScheduler) {
                Logger.info("Falling back to Alarm Scheduler.");
                scheduler = new AlarmScheduler();
                isGcmScheduler = false;
                dispatch(job);
            }
        }
    }

    /**
     * Helper method to reschedule jobs.
     *
     * @param job The job.
     */
    private void reschedule(Job job) {
        try {
           getScheduler().reschedule(context, job);
        } catch (SchedulerException e) {
            Logger.error("Scheduler failed to schedule job", e);

            if (isGcmScheduler) {
                Logger.info("Falling back to Alarm Scheduler.");
                scheduler = new AlarmScheduler();
                isGcmScheduler = false;
                reschedule(job);
            }
        }
    }


    /**
     * Cancels a job based on the job's tag.
     *
     * @param tag The job's tag.
     */
    public void cancel(@NonNull String tag) {
        try {
            getScheduler().cancel(context, tag);
        } catch (SchedulerException e) {
            Logger.error("Scheduler failed to cancel job with tag: " + tag, e);

            if (isGcmScheduler) {
                Logger.info("Falling back to Alarm Scheduler.");
                scheduler = new AlarmScheduler();
                isGcmScheduler = false;
                cancel(tag);
            }
        }
    }

    /**
     * Returns the scheduler.
     *
     * @return The scheduler.
     */
    private Scheduler getScheduler() {
        if (scheduler == null) {

            try {
                if (PlayServicesUtils.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS && PlayServicesUtils.isGoogleCloudMessagingDependencyAvailable()) {
                    scheduler = new GcmScheduler();
                    isGcmScheduler = true;
                } else {
                    scheduler = new AlarmScheduler();
                }
            } catch (IllegalStateException e) {
                scheduler = new AlarmScheduler();
            }
        }

        return scheduler;
    }


    /**
     * Runs a job.
     *
     * @param job The job to run.
     * @param callback Callback when the job is finished.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void runJob(@NonNull final Job job, @NonNull final Callback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final UAirship airship = UAirship.waitForTakeOff(AIRSHIP_WAIT_TIME_MS);
                if (airship == null) {
                    Logger.error("JobDispatcher - UAirship not ready. Rescheduling job: " + job);
                    callback.onFinish(job, Job.JOB_RETRY);
                    return;
                }

                final AirshipComponent component = findAirshipComponent(airship, job.getAirshipComponentName());
                if (component == null) {
                    Logger.error("JobDispatcher - Unavailable to find airship components for job: " + job);
                    callback.onFinish(job, Job.JOB_FINISHED);
                    return;
                }

                component.getJobExecutor(job).execute(new Runnable() {
                    @Override
                    public void run() {
                        int result = component.onPerformJob(airship, job);
                        Logger.verbose("JobDispatcher - Job finished: " + job + " with result: " + result);

                        if (result == Job.JOB_RETRY) {
                            reschedule(job);
                        }

                        callback.onFinish(job, result);
                    }
                });
            }
        });
    }

    /**
     * Finds the {@link AirshipComponent}s for a given job.
     *
     * @param componentClassName The component's class name.
     * @param airship The airship instance.
     * @return The airship component.
     */
    private AirshipComponent findAirshipComponent(UAirship airship, String componentClassName) {
        if (UAStringUtil.isEmpty(componentClassName)) {
            return null;
        }

        for (final AirshipComponent component : airship.getComponents()) {
            if (component.getClass().getName().equals(componentClassName)) {
                return component;
            }
        }

        return null;
    }
}
