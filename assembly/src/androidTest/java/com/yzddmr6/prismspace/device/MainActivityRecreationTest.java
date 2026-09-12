package com.yzddmr6.prismspace.device;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivityRecreationTest {
    private static final String MAIN = "com.yzddmr6.prismspace.MainActivity";

    @Test public void restoredMainScreenAttachesItsContentAfterRecreation() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.content.Context context = instrumentation.getTargetContext();
        instrumentation.startActivitySync(new Intent().setClassName(context, MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity original = awaitRenderedMain(null);
        instrumentation.runOnMainSync(original::recreate);
        awaitRenderedMain(original);
    }

    private Activity awaitRenderedMain(Activity previous) {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<Activity> current = new AtomicReference<>();
        AtomicBoolean rendered = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity == previous || !activity.getClass().getName().equals(MAIN)) continue;
                    int id = activity.getResources().getIdentifier("container", "id", activity.getPackageName());
                    ViewGroup container = activity.findViewById(id);
                    if (container != null && container.getChildCount() > 0 && container.getChildAt(0).getWidth() > 0) {
                        current.set(activity);
                        rendered.set(true);
                    }
                }
            });
            if (rendered.get()) return current.get();
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("MainActivity resumed with an empty or unattached screen after recreation", rendered.get());
        return current.get();
    }
}
