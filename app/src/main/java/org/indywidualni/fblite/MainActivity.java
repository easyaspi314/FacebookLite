package org.indywidualni.fblite;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;
import net.grandcentrix.tray.TrayAppPreferences;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    // variables for drawer layout
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerList;
    private String[] itemList;

    private SwipeRefreshLayout swipeRefreshLayout;
    private WebView webView;
    private ProgressBar progressBar;

    // variables for camera and choosing files methods
    private static final int FILECHOOSER_RESULTCODE = 1;
    private ValueCallback<Uri> mUploadMessage;
    private Uri mCapturedImageURI = null;

    // the same for Android 5.0 methods only
    private ValueCallback<Uri[]> mFilePathCallback;
    private String mCameraPhotoPath;

    // error handling, shared prefs and TrayPreferences
    private static final String TAG = MainActivity.class.getSimpleName();
    private SharedPreferences preferences;
    private TrayAppPreferences trayPreferences;

    @Override
    @SuppressLint("setJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // get shared preferences and TrayPreferences
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        trayPreferences = new TrayAppPreferences(getApplicationContext());

        // set the main content view (for drawer position)
        if ("0".equals(preferences.getString("drawer_pos", "0")))
            setContentView(R.layout.activity_main);
        else
            setContentView(R.layout.activity_main_drawer_right);

        // if the app is being launched for the first time
        if (preferences.getBoolean("first_run", true)) {
            // show quick start guide
            onCoachMark();
            // save the fact that the app has been started at least once
            preferences.edit().putBoolean("first_run", false).apply();
        }

        // start the service when it's activated but somehow it's not running (after app update for example)
        // when it's already running nothing happens so it's ok
        if (preferences.getBoolean("notifications_activated", false)) {
            final Intent intent = new Intent(MyApplication.getContextOfApplication(), NotificationsService.class);
            MyApplication.getContextOfApplication().startService(intent);
        }

        // KitKat layout fix
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT) {
            // apply top padding to avoid layout being hidden by the status bar
            LinearLayout contentMain = (LinearLayout) findViewById(R.id.content_main);
            contentMain.setPadding(0, getStatusBarHeight(), 0, 0);
            // bug fix for resizing the view while opening soft keyboard
            AndroidBug5497Workaround.assistActivity(this);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // transparent navBar (above KitKat) when it's enabled
            if (preferences.getBoolean("transparent_nav", false)) {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                // apply top padding to avoid layout being hidden by the status bar
                LinearLayout contentMain = (LinearLayout) findViewById(R.id.content_main);
                contentMain.setPadding(0, getStatusBarHeight(), 0, 0);
                // bug fix for resizing the view while opening soft keyboard
                AndroidBug5497Workaround.assistActivity(this);

                // bug fix (1.4.1) for launching the app in landscape mode
                if(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE && Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT)
                    contentMain.setPadding(0, getStatusBarHeight(), getNavigationBarHeight(getApplicationContext(), 0), 0);
                else if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE && Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                    contentMain.setPadding(0, 0, 0, getStatusBarHeight());
                }
            }
        }

        // piece of code for drawer layout
        itemList = getResources().getStringArray(R.array.item_array);
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawerList = (ListView) findViewById(R.id.left_drawer);
        // set up the drawer's list view with items and click listener
        mDrawerList.setAdapter(new ArrayAdapter<>(this, R.layout.drawer_list_item, itemList));
        mDrawerList.setOnItemClickListener(new DrawerItemClickListener());

        // disable hardware acceleration
        if (!preferences.getBoolean("hardware_acceleration", true)) {
            View root = mDrawerLayout.getRootView();
            root.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            Log.v("Hardware Acceleration", "disabled for this view");
        }

        // define url that will open in webView
        String webViewUrl = "http://m.facebook.com";

        swipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipe_container);
        swipeRefreshLayout.setOnRefreshListener(onRefreshListener);
        swipeRefreshLayout.setColorSchemeColors(Color.BLUE);

        // webView code without handling external links
        webView = (WebView) findViewById(R.id.webView1);
        webView.getSettings().setJavaScriptEnabled(true);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);

        //webView.getSettings().setUseWideViewPort(true);
        //webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setAllowFileAccess(true);

        // code optimization
        boolean isConnectedMobile = Connectivity.isConnectedMobile(getApplicationContext());
        boolean isFacebookZero = preferences.getBoolean("facebook_zero", false);

        // when someone clicks a Facebook link start the app with that link
        if ((getIntent() != null && getIntent().getDataString() != null) && (!isFacebookZero || !isConnectedMobile)) {
            webViewUrl = getIntent().getDataString();
            // show information about loading an external link
            Toast.makeText(getApplicationContext(), getString(R.string.loading_link), Toast.LENGTH_SHORT).show();
        } else if (isFacebookZero && isConnectedMobile) {
            // facebook zero if activated and connected to a mobile network
            webViewUrl = "https://0.facebook.com";
            Toast.makeText(getApplicationContext(), getString(R.string.facebook_zero_active), Toast.LENGTH_SHORT).show();
        }

        // if opened by a notification or a shortcut
        try {
            //noinspection ConstantConditions
            if (getIntent().getExtras().getString("start_url") != null) {
                String temp = getIntent().getExtras().getString("start_url");
                if (!isFacebookZero || !isConnectedMobile)
                    webViewUrl = temp;
                // cancel all notifications if 'All notifications' button was clicked
                if (temp.equals("https://m.facebook.com/notifications"))
                    NotificationsService.cancelAllNotifications();
            }
        } catch (Exception ignored) {}

        // notify when there is no internet connection
        if (!Connectivity.isConnected(getApplicationContext()))
            Toast.makeText(getApplicationContext(), getString(R.string.no_network), Toast.LENGTH_SHORT).show();

        // load url in webView
        webView.loadUrl(webViewUrl);
        webView.setWebViewClient(new MyAppWebViewClient());

        // implement WebChromeClient inner class
        // we will define openFileChooser for select file from camera
        webView.setWebChromeClient(new WebChromeClient() {

            // page loading progress, gone when fully loaded
            public void onProgressChanged(WebView view, int progress) {
                // display it only when it's enabled (default true)
                if (preferences.getBoolean("progress_bar", true)) {
                    if (progress < 100 && progressBar.getVisibility() == ProgressBar.GONE) {
                        progressBar.setVisibility(ProgressBar.VISIBLE);
                    }
                    progressBar.setProgress(progress);
                    if (progress == 100) {
                        progressBar.setVisibility(ProgressBar.GONE);
                    }
                } else {
                    // if progress bar is disabled hide it immediately
                    progressBar.setVisibility(ProgressBar.GONE);
                }
            }

            // for Lollipop, all in one
            public boolean onShowFileChooser(
                    WebView webView, ValueCallback<Uri[]> filePathCallback,
                    WebChromeClient.FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (takePictureIntent.resolveActivity(getPackageManager()) != null) {

                    // create the file where the photo should go
                    File photoFile = null;
                    try {
                        photoFile = createImageFile();
                        takePictureIntent.putExtra("PhotoPath", mCameraPhotoPath);
                    } catch (IOException ex) {
                        // Error occurred while creating the File
                        Log.e(TAG, "Unable to create Image File", ex);
                    }

                    // continue only if the file was successfully created
                    if (photoFile != null) {
                        mCameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                                Uri.fromFile(photoFile));
                    } else {
                        takePictureIntent = null;
                    }
                }

                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentSelectionIntent.setType("image/*");

                Intent[] intentArray;
                if (takePictureIntent != null) {
                    intentArray = new Intent[]{takePictureIntent};
                } else {
                    intentArray = new Intent[0];
                }

                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, getString(R.string.image_chooser));
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);

                startActivityForResult(chooserIntent, FILECHOOSER_RESULTCODE);

                return true;
            }

            // creating image files (Lollipop only)
            private File createImageFile() throws IOException {

                File imageStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FacebookLite");

                if (!imageStorageDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    imageStorageDir.mkdirs();
                }

                // create an image file name
                imageStorageDir  = new File(imageStorageDir + File.separator + "IMG_" + String.valueOf(System.currentTimeMillis()) + ".jpg");
                return imageStorageDir;
            }

            // openFileChooser for Android 3.0+
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
                mUploadMessage = uploadMsg;

                try {
                    File imageStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FacebookLite");

                    if (!imageStorageDir.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        imageStorageDir.mkdirs();
                    }

                    File file = new File(imageStorageDir + File.separator + "IMG_" + String.valueOf(System.currentTimeMillis()) + ".jpg");

                    mCapturedImageURI = Uri.fromFile(file); // save to the private variable

                    final Intent captureIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                    captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCapturedImageURI);
                    // captureIntent.putExtra(MediaStore.EXTRA_SCREEN_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");

                    Intent chooserIntent = Intent.createChooser(i, getString(R.string.image_chooser));
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{captureIntent});

                    startActivityForResult(chooserIntent, FILECHOOSER_RESULTCODE);
                } catch (Exception e) {
                    Toast.makeText(getBaseContext(), "Camera Exception:" + e, Toast.LENGTH_LONG).show();
                }

            }

            // not needed but let's make it overloaded just in case
            // openFileChooser for Android < 3.0
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {
                openFileChooser(uploadMsg, "");
            }

            // openFileChooser for other Android versions
            /** may not work on KitKat due to lack of implementation of openFileChooser() or onShowFileChooser()
             *  https://code.google.com/p/android/issues/detail?id=62220
             *  however newer versions of KitKat fixed it on some devices */
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                openFileChooser(uploadMsg, acceptType);
            }

        });

        // OnLongClickListener for detecting long clicks on links and images
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // activate long clicks on links and image links according to settings
                if (preferences.getBoolean("long_clicks", false)) {
                    WebView.HitTestResult result = webView.getHitTestResult();
                    if (result.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE || result.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                        Message msg = linkHandler.obtainMessage();
                        webView.requestFocusNodeHref(msg);
                        return true;
                    }
                }
                return false;
            }
        });

    }

    // handle long clicks on links, an awesome way to avoid memory leaks
    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public MyHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = mActivity.get();
            if (activity != null) {

                // get url to share
                String url = (String) msg.getData().get("url");

                if (url != null) {
                    // "clean" the url, remove Facebook tracking redirection while sharing
                    url = url.replace("http://lm.facebook.com/l.php?u=", "").replace("%3A", ":").replace("%2C", ",")
                            .replace("%2F", "/").replaceAll("&h=.*", "").replace("https://m.facebook.com/l.php?u=", "");

                    Log.v("Link long clicked", url);
                    // create share intent for long clicked url
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, url);
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_link)));
                }
            }
        }
    }

    // create linkHandler, OnLongClickListener wants it bad
    private final MyHandler linkHandler = new MyHandler(this);

    // get status bar height (needed for transparent nav bar)
    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    // get navigation bar height
    public static int getNavigationBarHeight(Context context, int orientation) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier(orientation == Configuration.ORIENTATION_PORTRAIT ? "navigation_bar_height" : "navigation_bar_height_landscape", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    // return here when file selected from camera or from SD Card
    @Override
    public void onActivityResult (int requestCode, int resultCode, Intent data) {

        // code for all versions except of Lollipop
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {

            if (requestCode==FILECHOOSER_RESULTCODE) {
                if (null == this.mUploadMessage) {
                    return;
                }

                Uri result=null;

                try {
                    if (resultCode != RESULT_OK) {
                        result = null;
                    } else {
                        // retrieve from the private variable if the intent is null
                        result = data == null ? mCapturedImageURI : data.getData();
                    }
                }
                catch(Exception e) {
                    Toast.makeText(getApplicationContext(), "activity :"+e, Toast.LENGTH_LONG).show();
                }

                mUploadMessage.onReceiveValue(result);
                mUploadMessage = null;
            }

        } // end of code for all versions except of Lollipop

        // start of code for Lollipop only
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            if (requestCode != FILECHOOSER_RESULTCODE || mFilePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }

            Uri[] results = null;

            // check that the response is a good one
            if (resultCode == Activity.RESULT_OK) {
                if (data == null) {
                    // if there is not data, then we may have taken a photo
                    if (mCameraPhotoPath != null) {
                        results = new Uri[]{Uri.parse(mCameraPhotoPath)};
                    }
                } else {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
            }

            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;

        } // end of code for Lollipop only

    }

    SwipeRefreshLayout.OnRefreshListener onRefreshListener = new SwipeRefreshLayout.OnRefreshListener() {

        // refreshing pages
        @Override
        public void onRefresh() {
            // notify when there is no internet connection
            if (!Connectivity.isConnected(getApplicationContext()))
                Toast.makeText(getApplicationContext(), getString(R.string.no_network), Toast.LENGTH_SHORT).show();

            // reloading page
            webView.reload();

            new Handler().postDelayed(new Runnable() {

                @Override
                public void run() {
                    swipeRefreshLayout.setRefreshing(false);
                    // done!
                }

            }, 2000);
        }};

    // the click listener for ListView in the navigation drawer
    private class DrawerItemClickListener implements ListView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            selectItem(position);
        }
    }

    // when a drawer item is clicked do instructions from below
    private void selectItem(int position) {
        // if someone is using Facebook Zero the menu is different
        if (preferences.getBoolean("facebook_zero", false) && Connectivity.isConnectedMobile(getApplicationContext())) {
            switch (position) {
                case 0:
                    webView.loadUrl("javascript:scroll(0,0)");
                    break;
                case 1:
                    webView.loadUrl("https://0.facebook.com");
                    break;
                case 2:
                    webView.loadUrl("https://0.facebook.com/messages");
                    break;
                case 3:
                    webView.loadUrl("https://0.facebook.com/buddylist.php");
                    break;
                case 4:
                    webView.loadUrl("https://0.facebook.com/groups/?category=membership");
                    break;
                case 5:
                    webView.loadUrl("https://0.facebook.com/events");
                    break;
                case 6:
                    Intent settings = new Intent(this, SettingsActivity.class);
                    startActivity(settings);
                    break;
                case 7:
                    Intent about = new Intent(this, AboutActivity.class);
                    startActivity(about);
                    break;
                case 8:
                    trayPreferences.put("activity_visible", false);
                    //finish();
                    System.exit(0); // ugly, ugly, ugly!
                    break;
                default:
                    // silence is golden
                    break;
            }
        } else {
            // standard application menu (it's default)
            switch (position) {
                case 0:
                    webView.loadUrl("javascript:scroll(0,0)");
                    break;
                case 1:
                    webView.loadUrl("https://m.facebook.com");
                    break;
                case 2:
                    webView.loadUrl("https://m.facebook.com/messages");
                    break;
                case 3:
                    webView.loadUrl("https://m.facebook.com/buddylist.php");
                    break;
                case 4:
                    webView.loadUrl("https://m.facebook.com/groups/?category=membership");
                    break;
                case 5:
                    webView.loadUrl("https://m.facebook.com/events");
                    break;
                case 6:
                    Intent settings = new Intent(this, SettingsActivity.class);
                    startActivity(settings);
                    break;
                case 7:
                    Intent about = new Intent(this, AboutActivity.class);
                    startActivity(about);
                    break;
                case 8:
                    trayPreferences.put("activity_visible", false);
                    //finish();
                    System.exit(0); // ugly, ugly, ugly!
                    break;
                default:
                    // silence is golden
                    break;
            }
        }
        // update selected item, then close the drawer
        mDrawerList.setItemChecked(position, true);
        mDrawerLayout.closeDrawer(mDrawerList);
    }

    // survive screen orientation change
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // bug fix (1.4.1) for landscape mode
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && preferences.getBoolean("transparent_nav", false)) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT) {
                LinearLayout contentMain = (LinearLayout) findViewById(R.id.content_main);
                contentMain.setPadding(0, getStatusBarHeight(), getNavigationBarHeight(getApplicationContext(), 0), 0);
            } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                LinearLayout contentMain = (LinearLayout) findViewById(R.id.content_main);
                contentMain.setPadding(0, 0, 0, getStatusBarHeight());
            }
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && preferences.getBoolean("transparent_nav", false)) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT) {
                LinearLayout contentMain = (LinearLayout) findViewById(R.id.content_main);
                contentMain.setPadding(0, getStatusBarHeight(), 0, 0);
            } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                LinearLayout contentMain = (LinearLayout) findViewById(R.id.content_main);
                contentMain.setPadding(0, getStatusBarHeight(), 0, 0);
            }
        }
    }

    // app is already running and gets a new intent
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // grab an url if opened by clicking a link
        String webViewUrl = getIntent().getDataString();

        // if opened by a notification or a shortcut
        try {
            if (getIntent().getExtras().getString("start_url") != null)
                webViewUrl = getIntent().getExtras().getString("start_url");
                // cancel all notifications if 'All notifications' button was clicked
                if (webViewUrl.equals("https://m.facebook.com/notifications"))
                    NotificationsService.cancelAllNotifications();
        } catch (Exception ignored) {}

        // load a grabbed url instead of the current page
        if (preferences.getBoolean("facebook_zero", false) && Connectivity.isConnectedMobile(getApplicationContext()))
            Toast.makeText(getApplicationContext(), getString(R.string.facebook_zero_active), Toast.LENGTH_SHORT).show();
        else
            webView.loadUrl(webViewUrl);

        // notify when there is no internet connection
        if (!Connectivity.isConnected(getApplicationContext()))
            Toast.makeText(getApplicationContext(), getString(R.string.no_network), Toast.LENGTH_SHORT).show();

        // recreate activity when something important was just changed
        if (getIntent().getBooleanExtra("core_settings_changed", false)) {
            finish(); // finish and create a new Instance
            Intent restart = new Intent(MainActivity.this, MainActivity.class);
            startActivity(restart);
        }
    }

    // handling back button
    @Override
    public void onBackPressed() {
        if(webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        trayPreferences.put("activity_visible", true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        trayPreferences.put("activity_visible", false);
    }

    @Override
    public void onDestroy() {
        Log.i("MainActivity", "onDestroy: Destroying...");
        super.onDestroy();
        webView.removeAllViews();
        webView.destroy();
    }

    // first run dialog with introduction
    private void onCoachMark() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.setContentView(R.layout.coach_mark);
        dialog.setCanceledOnTouchOutside(true);
        //for dismissing anywhere you touch
        View masterView = dialog.findViewById(R.id.coach_mark_master_view);
        masterView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

}