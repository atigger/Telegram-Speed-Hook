package Telegram.Speed.Hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Switch;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class MainActivity extends Activity {
    private SharedPreferences preferences;
    private LiveLogView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String[] profiles = getResources().getStringArray(R.array.profile_options);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(getString(R.string.title, BuildConfig.VERSION_NAME));
        title.setTextSize(20);
        add(content, title);

        TextView description = new TextView(this);
        description.setText(R.string.description);
        description.setTextSize(16);
        add(content, description);

        Spinner profileSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, profiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        add(content, profileSpinner);
        Switch video = new Switch(this);
        video.setText(R.string.video_switch);
        add(content, video);
        TextView videoHelp = new TextView(this);
        videoHelp.setText(R.string.video_help);
        add(content, videoHelp);

        Button save = new Button(this);
        save.setText(R.string.save_button);
        add(content, save);
        TextView status = new TextView(this);
        status.setTextSize(16);
        add(content, status);

        try {
            preferences = getSharedPreferences(SpeedPolicy.PREFERENCES, MODE_WORLD_READABLE);
            int selected = preferences.getInt(SpeedPolicy.PROFILE_KEY, SpeedPolicy.PROFILE_FAST);
            if (selected < 0 || selected >= profiles.length) {
                selected = SpeedPolicy.PROFILE_ORIGINAL;
            }
            profileSpinner.setSelection(selected);
            video.setChecked(preferences.getBoolean(SpeedPolicy.VIDEO_KEY, false));
        } catch (SecurityException error) {
            save.setEnabled(false);
            video.setEnabled(false);
            status.setText(R.string.settings_unavailable);
        }

        save.setOnClickListener(view -> {
            logView.stop();
            int profile = profileSpinner.getSelectedItemPosition();
            boolean videoEnabled = video.isChecked();
            save.setEnabled(false);
            profileSpinner.setEnabled(false);
            video.setEnabled(false);
            status.setText(R.string.saving);
            new Thread(() -> saveAndStop(profile, profiles[profile], videoEnabled,
                    save, profileSpinner, video, status),
                    "tg-speed-settings").start();
        });

        logView = new LiveLogView(this);
        add(content, logView);

        TextView details = new TextView(this);
        details.setText(R.string.details);
        Linkify.addLinks(details, Linkify.WEB_URLS);
        details.setMovementMethod(LinkMovementMethod.getInstance());
        details.setTextSize(15);
        add(content, details);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        logView.setForeground(true);
    }

    @Override
    protected void onPause() {
        logView.setForeground(false);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        logView.onExportResult(requestCode, resultCode, data);
    }

    private void add(LinearLayout content, android.view.View view) {
        content.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void saveAndStop(int profile, String profileLabel, boolean videoEnabled, Button save,
                             Spinner spinner, Switch video, TextView status) {
        String message;
        try {
            boolean saved = preferences.edit().putInt(SpeedPolicy.PROFILE_KEY, profile)
                    .putBoolean(SpeedPolicy.VIDEO_KEY, videoEnabled).commit();
            message = !saved ? getString(R.string.save_failed)
                    : getString(R.string.saved_profile, profileLabel) + "\n"
                    + getString(videoEnabled ? R.string.video_saved_on : R.string.video_saved_off)
                    + "\n" + stopTelegram();
        } catch (RuntimeException error) {
            message = getString(R.string.operation_failed);
        }
        String result = message;
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                status.setText(result);
                save.setEnabled(true);
                spinner.setEnabled(true);
                video.setEnabled(true);
            }
        });
    }

    private String stopTelegram() {
        try {
            RootShell.Result result = RootShell.run("am force-stop org.telegram.messenger");
            return getString(result.exitCode == 0 ? R.string.stop_success : R.string.stop_failed);
        } catch (TimeoutException error) {
            return getString(R.string.root_timeout);
        } catch (IOException error) {
            return getString(R.string.su_failed);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return getString(R.string.stop_interrupted);
        }
    }
}
