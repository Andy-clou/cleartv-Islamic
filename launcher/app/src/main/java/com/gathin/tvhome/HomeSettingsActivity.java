package com.gathin.tvhome;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Lets the user change HOME through the system UI, without shell or privileged permissions. */
public class HomeSettingsActivity extends Activity {
    private static final int REQUEST_HOME_ROLE = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Toast.makeText(this, R.string.home_settings_already_default, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                try {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE);
                    return;
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    // Some TV builds expose the role but omit its request screen; try their Home settings page.
                }
            }
        }
        openHomeSettings();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_HOME_ROLE) return;
        Toast.makeText(this, resultCode == RESULT_OK ? R.string.home_settings_applied
                : R.string.home_settings_cancelled, Toast.LENGTH_LONG).show();
        finish();
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }
}
