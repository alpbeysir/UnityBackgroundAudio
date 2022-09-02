package com.alpbeysir.backgroundaudio;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import android.util.Log;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.DefaultMediaDescriptionAdapter;
import com.google.android.exoplayer2.ui.PlayerNotificationManager;

import java.io.File;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

public class BackgroundAudioService extends Service implements Player.Listener {
    public static BackgroundAudioInterface baInterface;
    private static ExoPlayer player;
    private static final String NOTIFICATION_CHANNEL_ID = "audioNotificationChannel";

    private static final String EXTRA_PATH = "audioPath";
    private static final String EXTRA_TITLE = "audioTitle";
    private static final String EXTRA_DESC = "audioDesc";
    private static final String EXTRA_ICON_URI = "iconUri";

    private static final String ACTION_DISPOSE = "dispose";
    private static final String ACTION_START = "start";
    private static final String ACTION_SHOW_NOTIFICATION = "notification";
    private static final String ACTION_INITIALIZE = "initialize";

    private static final String TAG = "BackgroundAudio";

    private static final int NOTIFICATION_ID = 1;
    private static NotificationCompat.Builder builder;

    private static Activity unityPlayerActivity;
    private static boolean paused;

    private static Handler mainHandler;
    PlayerNotificationManager pnm;
    PlayerNotificationManager.MediaDescriptionAdapter ad;

    private final IntentFilter becomingNoisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    private final BecomingNoisyReceiver becomingNoisyReceiver = new BecomingNoisyReceiver();

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        switch (intent.getAction()) {
            case ACTION_START: {
                String path = intent.getStringExtra(EXTRA_PATH);

                Log.d(TAG, String.format("Starting player on path %s", path));

                posCache = 0;
                if (pnm != null)
                    pnm.setPlayer(null);

                MediaItem item = MediaItem.fromUri(path);

                player.setMediaItem(item);
                player.prepare();
                player.play();

                //Make sure playback stops on headphones disconnected
                registerReceiver(becomingNoisyReceiver, becomingNoisyFilter);

                PlayerNotificationManager.Builder builder = new PlayerNotificationManager.Builder(this, NOTIFICATION_ID, NOTIFICATION_CHANNEL_ID);
                builder.setMediaDescriptionAdapter(ad);
                builder.setNotificationListener(new PlayerNotificationManager.NotificationListener() {
                    @Override
                    public void onNotificationPosted(int notificationId, Notification notification, boolean ongoing) {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                });

                pnm = builder.build();
                pnm.setUseRewindActionInCompactView(true);
                pnm.setUseFastForwardActionInCompactView(true);
                pnm.setUseNextActionInCompactView(true);
                pnm.setUsePreviousActionInCompactView(true);

                pnm.setPlayer(player);
                break;
            }
            case ACTION_DISPOSE: {
                pnm.setPlayer(null);
                player.release();
                baInterface.Stopped();
                stopSelf();
                break;
            }
            case ACTION_SHOW_NOTIFICATION:
                String title = intent.getStringExtra(EXTRA_TITLE);
                String desc = intent.getStringExtra(EXTRA_DESC);
                String iconUri = intent.getStringExtra(EXTRA_ICON_URI);

                //Back to app intent
                Intent resultIntent = new Intent(this, unityPlayerActivity.getClass());
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_IMMUTABLE);

                ad = new PlayerNotificationManager.MediaDescriptionAdapter() {
                    @Override
                    public CharSequence getCurrentContentTitle(Player player) {
                        return title;
                    }

                    @Nullable
                    @Override
                    public PendingIntent createCurrentContentIntent(Player player) {
                        return pendingIntent;
                    }

                    @Nullable
                    @Override
                    public CharSequence getCurrentContentText(Player player) {
                        return desc;
                    }

                    @Nullable
                    @Override
                    public Bitmap getCurrentLargeIcon(Player player, PlayerNotificationManager.BitmapCallback callback) {
                        Bitmap bitmap = BitmapFactory.decodeFile(iconUri);
                        return bitmap;
                    }
                };
                break;
            case ACTION_INITIALIZE:
                player = new ExoPlayer.Builder(unityPlayerActivity).build();
                player.addListener(this);
                mainHandler = new Handler(unityPlayerActivity.getMainLooper());
                break;
        }
        return START_NOT_STICKY;
    }

    public static void start(String path) {
        if (unityPlayerActivity == null)  {
            Log.e(TAG, "Must call initialize before using service");
            return;
        }

        Intent intent = new Intent(unityPlayerActivity, BackgroundAudioService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_PATH, path);

        unityPlayerActivity.startService(intent);
    }

    public static void showNotification(String title, String desc, String iconUri) {
        if (unityPlayerActivity == null)  {
            Log.e(TAG, "Must call initialize before using service");
            return;
        }

        Intent intent = new Intent(unityPlayerActivity, BackgroundAudioService.class);
        intent.setAction(ACTION_SHOW_NOTIFICATION);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DESC, desc);
        intent.putExtra(EXTRA_ICON_URI, iconUri);

        unityPlayerActivity.startService(intent);
    }

    public static void pause() {
        Runnable runnable = () -> {
            player.pause();
            paused = true;
            baInterface.Paused();
        };
        mainHandler.post(runnable);
    }
    public static void resume() {
        Runnable runnable = () -> {
            player.play();
            paused = false;
            baInterface.Resumed();
        };
        mainHandler.post(runnable);
    }

    public static void stop() {
        Runnable runnable = () -> {
            if (!player.isPlaying()) return;

            player.stop();
            baInterface.Stopped();
        };
        mainHandler.post(runnable);
    }

    public static void dispose() {
        Intent intent = new Intent(unityPlayerActivity, BackgroundAudioService.class);
        intent.setAction(ACTION_DISPOSE);
        unityPlayerActivity.startService(intent);
    }

    public static void initialize(Activity act, BackgroundAudioInterface _baInterface) {
        unityPlayerActivity = act;
        baInterface = _baInterface;

        Intent intent = new Intent(unityPlayerActivity, BackgroundAudioService.class);
        intent.setAction(ACTION_INITIALIZE);
        unityPlayerActivity.startService(intent);
    }

    public static boolean isPaused() {
        return paused;
    }

    private static long posCache;

    private static RunnableFuture<Void> task;
    private static final Callable<Void> runnable = () -> {
        posCache = player.getCurrentPosition();
        return null;
    };

    public static float getPosition() {
        if (task == null || task.isDone()) {
            task = new FutureTask<Void>(runnable);
            mainHandler.post(task);
        }
        return (float)posCache / 1000f;
    }

    public static void setPosition(float seconds) {
        Runnable runnable = () -> player.seekTo((int)(seconds * 1000));
        mainHandler.post(runnable);
    }

    //This will run on UnityMain, TODO refactor
    public static float getDuration() {
        return (float)player.getDuration() / 1000f;
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying) baInterface.Resumed();
        else baInterface.Paused();
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
        if (playbackState == Player.STATE_READY) {
            baInterface.Prepared();
            baInterface.Resumed();
        }
        if (playbackState == Player.STATE_ENDED) baInterface.Stopped();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        baInterface.Error(0, 0);
    }


    private class BecomingNoisyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            pnm.setPlayer(null);
            player.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
