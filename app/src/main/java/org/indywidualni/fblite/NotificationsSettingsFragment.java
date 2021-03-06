package org.indywidualni.fblite;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.RingtonePreference;

public class NotificationsSettingsFragment extends PreferenceFragment {

    private static Context context;
    private SharedPreferences preferences;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // load the preferences from an XML resource
        addPreferencesFromResource(R.xml.notifications_preferences);

        context = MyApplication.getContextOfApplication();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // default value for interval_pref preference summary
        ListPreference lp = (ListPreference) findPreference("interval_pref");
        String temp1 = getString(R.string.interval_pref_description).replace("%s", "");
        String temp2 = lp.getSummary().toString();
        if (temp1.equals(temp2))
            lp.setValueIndex(2);
    }

    @Override
    public void onResume() {
        super.onResume();

        // update ringtone preference summary
        String ringtoneString = preferences.getString("ringtone", "content://settings/system/notification_sound");
        Uri ringtoneUri = Uri.parse(ringtoneString);
        Ringtone ringtone = RingtoneManager.getRingtone(context, ringtoneUri);
        String name = ringtone.getTitle(context);
        if ("".equals(ringtoneString))
            name = getString(R.string.silent);
        RingtonePreference rp = (RingtonePreference) findPreference("ringtone");
        rp.setSummary(getString(R.string.notification_sound_description) + name);
    }

}