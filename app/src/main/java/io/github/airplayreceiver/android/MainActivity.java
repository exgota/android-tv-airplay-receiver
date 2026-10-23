package io.github.airplayreceiver.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import io.github.airplayreceiver.R;

/** Status screen: shows the advertised name and address, and lets the user rename the receiver. */
public final class MainActivity extends Activity implements ReceiverService.StatusObserver {

    private ReceiverSettings settings;
    private TextView nameView;
    private TextView addressView;
    private TextView activityView;
    private Button startOnBootButton;
    private Button resolutionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        settings = new ReceiverSettings(this);
        nameView = (TextView) findViewById(R.id.receiver_name);
        addressView = (TextView) findViewById(R.id.receiver_address);
        activityView = (TextView) findViewById(R.id.receiver_activity);
        startOnBootButton = (Button) findViewById(R.id.start_on_boot_button);
        resolutionButton = (Button) findViewById(R.id.resolution_button);
        resolutionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 1080p, then 4K where the hardware can show it, then 720p, then back.
                int height = settings.videoHeight();
                int next;
                if (height == 1080) {
                    next = ReceiverService.supportsUltraHighDefinition(MainActivity.this) ? 2160 : 720;
                } else if (height == 2160) {
                    next = 720;
                } else {
                    next = 1080;
                }
                settings.setVideoHeight(next);
                updateResolutionLabel();
                ReceiverService.restart(MainActivity.this);
            }
        });
        updateResolutionLabel();

        findViewById(R.id.rename_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRenameDialog();
            }
        });
        startOnBootButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                settings.setStartOnBoot(!settings.startOnBoot());
                updateStartOnBootLabel();
            }
        });
        findViewById(R.id.restart_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ReceiverService.restart(MainActivity.this);
            }
        });
        updateStartOnBootLabel();
        ReceiverService.start(this);
        findViewById(R.id.rename_button).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReceiverService.setStatusObserver(this);
    }

    @Override
    protected void onPause() {
        ReceiverService.setStatusObserver(null);
        super.onPause();
    }

    @Override
    public void onStatusChanged(ReceiverService.Status status) {
        nameView.setText(status.name.isEmpty() ? settings.name() : status.name);
        addressView.setText(status.address.isEmpty() ? "" : "AirPlay at " + status.address);
        activityView.setText(status.activity);
    }

    private void updateResolutionLabel() {
        int height = settings.videoHeight();
        if (height >= 2160 && !ReceiverService.supportsUltraHighDefinition(this)) {
            height = 1080;
        }
        resolutionButton.setText(height >= 2160 ? R.string.resolution_2160
                : height <= 720 ? R.string.resolution_720 : R.string.resolution_1080);
    }

    private void updateStartOnBootLabel() {
        startOnBootButton.setText(settings.startOnBoot() ? R.string.start_on_boot_on : R.string.start_on_boot_off);
    }

    private void showRenameDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSingleLine(true);
        input.setText(settings.name());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setView(input)
                .setPositiveButton(R.string.rename_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim().replaceAll("[\\p{Cntrl}]", "");
                        if (!name.isEmpty() && name.length() <= 60 && !name.equals(settings.name())) {
                            settings.setName(name);
                            ReceiverService.restart(MainActivity.this);
                        }
                    }
                })
                .setNegativeButton(R.string.rename_cancel, null)
                .show();
    }
}
