package com.mycar.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.List;

/** Что играет сейчас и управление плеером (пауза, следующий, предыдущий). */
final class PlayerHelper {

    private static final String[] BROADCASTS = {
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.android.music.playbackcomplete",
            "com.spotify.music.metadatachanged",
            "com.spotify.music.playbackstatechanged"
    };

    private final Context context;
    private final AudioManager audio;
    private MediaController controller;
    private boolean registered;

    volatile String title;
    volatile String artist;
    volatile boolean playing;
    volatile boolean hasAccess;

    private volatile String legacyTitle;
    private volatile String legacyArtist;
    private volatile boolean legacyPlaying;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (action.endsWith("metachanged") || action.endsWith("metadatachanged")) {
                String track = intent.getStringExtra("track");
                if (track != null) {
                    legacyTitle = track;
                    legacyArtist = intent.getStringExtra("artist");
                }
                legacyPlaying = intent.getBooleanExtra("playing", true);
            } else if (action.endsWith("playstatechanged") || action.endsWith("playbackstatechanged")) {
                legacyPlaying = intent.getBooleanExtra("playing", false);
            } else if (action.endsWith("playbackcomplete")) {
                legacyPlaying = false;
            }
        }
    };

    PlayerHelper(Context context) {
        this.context = context;
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    void register() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        for (String a : BROADCASTS) filter.addAction(a);
        context.registerReceiver(receiver, filter);
        registered = true;
    }

    void unregister() {
        if (!registered) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // уже снят
        }
        registered = false;
    }

    static boolean listenerEnabled(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(context.getPackageName());
    }

    /** Есть ли хоть какие-то данные о треке (из системы или из широковещательных сообщений плеера). */
    boolean hasData() {
        return controller != null || legacyTitle != null;
    }

    void refresh() {
        hasAccess = listenerEnabled(context);
        controller = null;

        if (hasAccess) {
            try {
                MediaSessionManager msm =
                        (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
                List<MediaController> list =
                        msm.getActiveSessions(new ComponentName(context, MediaListener.class));
                for (MediaController mc : list) {
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                        controller = mc;
                        break;
                    }
                }
                if (controller == null && !list.isEmpty()) controller = list.get(0);
            } catch (SecurityException e) {
                hasAccess = false;
            } catch (Exception ignored) {
                // служба мультимедиа недоступна
            }
        }

        if (controller != null) {
            MediaMetadata md = controller.getMetadata();
            String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null && md != null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            PlaybackState ps = controller.getPlaybackState();
            title = t;
            artist = a;
            playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        } else {
            title = legacyTitle;
            artist = legacyArtist;
            playing = legacyPlaying;
        }
    }

    void playPause() {
        if (controller != null) {
            if (playing) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
        } else {
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    void next() {
        if (controller != null) {
            controller.getTransportControls().skipToNext();
        } else {
            sendKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        }
    }

    void previous() {
        if (controller != null) {
            controller.getTransportControls().skipToPrevious();
        } else {
            sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    /** Запасной способ: медиакнопка уходит тому приложению, которое сейчас управляет звуком. */
    private void sendKey(int code) {
        if (audio == null) return;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }
}
