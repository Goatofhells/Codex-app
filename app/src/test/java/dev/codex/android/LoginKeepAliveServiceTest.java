package dev.codex.android;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class LoginKeepAliveServiceTest {
    @Test public void signInServiceIsVisibleAndDoesNotRestartItself() {
        ServiceController<LoginKeepAliveService> controller=Robolectric.buildService(LoginKeepAliveService.class).create();
        try {
            LoginKeepAliveService service=controller.get();
            assertEquals(Service.START_NOT_STICKY,service.onStartCommand(new Intent(),0,1));
            Notification notification=shadowOf(service).getLastForegroundNotification();
            assertNotNull(notification);
            assertEquals("Finishing Codex sign-in",notification.extras.getString(Notification.EXTRA_TITLE));
            assertNotNull(notification.contentIntent);
        } finally { controller.destroy(); }
    }
}
