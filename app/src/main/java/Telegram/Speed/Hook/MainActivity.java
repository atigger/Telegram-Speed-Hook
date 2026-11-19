package Telegram.Speed.Hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private SharedPreferences preferences;

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
        } catch (SecurityException error) {
            save.setEnabled(false);
            status.setText(R.string.settings_unavailable);
        }

        save.setOnClickListener(view -> {
            int profile = profileSpinner.getSelectedItemPosition();
            save.setEnabled(false);
            profileSpinner.setEnabled(false);
            status.setText(R.string.saving);
            new Thread(() -> saveAndStop(profile, profiles[profile], save, profileSpinner, status),
                    "tg-speed-settings").start();
        });

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

    private void add(LinearLayout content, android.view.View view) {
        content.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void saveAndStop(int profile, String profileLabel, Button save,
                             Spinner spinner, TextView status) {
        String message;
        try {
            boolean saved = preferences.edit().putInt(SpeedPolicy.PROFILE_KEY, profile).commit();
            message = !saved ? getString(R.string.save_failed)
                    : getString(R.string.saved_profile, profileLabel) + "\n" + stopTelegram();
        } catch (RuntimeException error) {
            message = getString(R.string.operation_failed);
        }
        String result = message;
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                status.setText(result);
                save.setEnabled(true);
                spinner.setEnabled(true);
            }
        });
    }

    private String stopTelegram() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c",
                    "am force-stop org.telegram.messenger").redirectErrorStream(true).start();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !process.waitFor(30, TimeUnit.SECONDS)) {
                return getString(R.string.root_timeout);
            }
            int exitCode = process.waitFor();
            return exitCode == 0 ? getString(R.string.stop_success)
                    : getString(R.string.stop_failed);
        } catch (IOException error) {
            return getString(R.string.su_failed);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return getString(R.string.stop_interrupted);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
