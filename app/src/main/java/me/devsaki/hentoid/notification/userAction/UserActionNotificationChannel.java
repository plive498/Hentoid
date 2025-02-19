package me.devsaki.hentoid.notification.userAction;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Objects;

import me.devsaki.hentoid.R;

public class UserActionNotificationChannel {

    private UserActionNotificationChannel() {
        throw new IllegalStateException("Utility class");
    }

    static final String ID = "user action";

    // IMPORTANT : ALWAYS INIT THE CHANNEL BEFORE FIRING NOTIFICATIONS !
    public static void init(@NonNull Context context) {
        String name = context.getString(R.string.notification_user_action_title);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(ID, name, importance);
        channel.setSound(null, null);
        channel.setVibrationPattern(null);

        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);

        // Mandatory; it is not possible to change the sound of an existing channel after its initial creation
        Objects.requireNonNull(notificationManager, "notificationManager must not be null");
        notificationManager.createNotificationChannel(channel);
    }
}
