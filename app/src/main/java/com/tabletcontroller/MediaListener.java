package com.tabletcontroller;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.content.ComponentName;
import android.util.Log;
import java.util.List;

public class MediaListener extends NotificationListenerService {
    private static final String TAG = "TabletController";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        updateMediaStatus();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        updateMediaStatus();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        updateMediaStatus();
    }

    private void updateMediaStatus() {
        if (MqttService.instance == null) return;
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> controllers = msm.getActiveSessions(
                new ComponentName(this, MediaListener.class));

            if (controllers.isEmpty()) {
                MqttService.instance.publish(MqttService.TOPIC_MEDIA_STATE, "idle");
                MqttService.instance.publish(MqttService.TOPIC_MEDIA_TITLE, "");
                MqttService.instance.publish(MqttService.TOPIC_MEDIA_ARTIST, "");
                return;
            }

            MediaController controller = controllers.get(0);
            PlaybackState state = controller.getPlaybackState();
            MediaMetadata metadata = controller.getMetadata();

            String playState = (state != null && state.getState() == PlaybackState.STATE_PLAYING)
                ? "playing" : "idle";
            MqttService.instance.publish(MqttService.TOPIC_MEDIA_STATE, playState);

            if (metadata != null) {
                String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                MqttService.instance.publish(MqttService.TOPIC_MEDIA_TITLE, title != null ? title : "");
                MqttService.instance.publish(MqttService.TOPIC_MEDIA_ARTIST, artist != null ? artist : "");
            }
        } catch (Exception e) {
            Log.e(TAG, "Media update error: " + e.getMessage());
        }
    }
}
