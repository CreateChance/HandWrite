package cn.ac.iscas.handwriter;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.app.Activity;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import cn.ac.iscas.handwriter.views.SignaturePad;

public class MainActivity extends Activity {

    private final String TAG = "MainActivity";

    private SignaturePad mSignaturePad;
    private Button mClearButton;
    private Button mSaveButton;
    private TextView mSignaturepadDescription;

    private final String RECORDS_FILE_NAME = "SignaturePadRecords.txt";
    private FileOutputStream mRecordsFileOutStream = null;

    private final int ERROR_SDCARD_UNMOUNTED_OR_DENIED = 100;
    private final int ERROR_FILE_CREATES_FAILED = 101;
    private final int EXTERNAL_CHECK_OK = 102;

    private PopupMenu popupMenu = null;

    // database
    private MyDatabase mDatabase = null;

    // shared prefs
    private final String PREFS_USER = "cn.ac.iscas.handwriter.currentuser";
    private SharedPreferences mUserPreferences;
    private SharedPreferences.Editor mUserEditor;
    private String mCurrentUsername = null;
    private int mCurrentUserID;
    private int mCurrentSampleID = 0;
    private int mNextUserId = 1;
    private final String CURRENT_USERNAME_KEY = "CurrentUsername";
    private final String CURRENT_USER_ID_KEY = "CurrentUserID";
    private final String CURRENT_USER_SAMPLEID_KEY = "CurrentUserSampleID";
    private final String NEXT_USER_ID_KEY = "NextUserID";

    // writing tags
    private final String SIGNATUREID_TAG = "SignatureID:";
    private final String SIGNATURELABLE_TAG = "SignatureLable:";

    private Handler mWriteEventHandler = null;
    private final int MSG_WRITE_COMPLETE = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // init database.
        mDatabase = new MyDatabase(this);

        mWriteEventHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);

                switch (msg.what) {
                    case MSG_WRITE_COMPLETE:
                        // clear pad when complete.
                        mSignaturePad.clear();
                        mSignaturepadDescription.setText(getString(R.string.hint_info) + mCurrentUsername +
                                ":" + mCurrentUserID + ":" + mCurrentSampleID);
                        fullScreenDisplay();
                        break;
                }
            }
        };

        mSignaturePad = (SignaturePad) findViewById(R.id.signature_pad);
        fullScreenDisplay();

        mSignaturePad.setOnSignedListener(new SignaturePad.OnSignedListener() {
            @Override
            public void onStartSigning() {
                if (mCurrentUsername == null) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.no_username_dialog_title)
                            .setMessage(R.string.no_username_dialog_message)
                            .setIcon(android.R.drawable.stat_sys_warning)
                            .setCancelable(false)
                            .setPositiveButton("ok", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    mSignaturePad.clear();
                                    // clear old records.
                                    mSignaturePad.getMotionEventRecord().clear();
                                    dialog.dismiss();
                                }
                            }).create().show();
                } else {
                    fullScreenDisplay();
                }
            }

            @Override
            public void onSigned() {
                mSaveButton.setEnabled(true);
                mClearButton.setEnabled(true);
            }

            @Override
            public void onClear() {
                mSaveButton.setEnabled(false);
                mClearButton.setEnabled(false);
            }
        });

        mClearButton = (Button) findViewById(R.id.clear_button);
        mSaveButton = (Button) findViewById(R.id.save_button);
        mSignaturepadDescription = (TextView) findViewById(R.id.signature_pad_description);

        mClearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mSignaturePad.clear();
                // clear old records.
                mSignaturePad.getMotionEventRecord().clear();
            }
        });

        mSaveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // show dialog, let user tell us whether this signature is true.
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle(R.string.save_confirm_dialog_title)
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setCancelable(false)
                        .setSingleChoiceItems(new String[]{"否", "是"}, 0, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // save this motion event to local txt file here.
                                SparseArray<SignaturePad.MotionEventRecorder> records = mSignaturePad.getMotionEventRecord();
                                new WriteRecordsThread(records, which).start();
                                dialog.dismiss();
                            }
                        }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                fullScreenDisplay();
                                dialog.dismiss();
                            }
                        }).create().show();
            }
        });

        // init prefs
        mUserPreferences = getSharedPreferences(PREFS_USER, MODE_PRIVATE);
        mUserEditor = mUserPreferences.edit();

        // get settings last time.
        mCurrentUsername = mUserPreferences.getString(CURRENT_USERNAME_KEY, null);
        mCurrentUserID = mUserPreferences.getInt(CURRENT_USER_ID_KEY, 0);
        mNextUserId = mUserPreferences.getInt(NEXT_USER_ID_KEY, 1);
        mCurrentSampleID = mUserPreferences.getInt(CURRENT_USER_SAMPLEID_KEY, 0);
        mSignaturepadDescription.setText(getString(R.string.hint_info) + mCurrentUsername +
                ":" + mCurrentUserID + ":" + mCurrentSampleID);

        int ret = checkExternalFile();
        String title = null;
        String message = null;

        Log.d(TAG, "ret: " + ret);

        switch (ret) {
            case ERROR_SDCARD_UNMOUNTED_OR_DENIED:
                title = getString(R.string.check_sdcard_unmounted_or_denied_title);
                message = getString(R.string.check_sdcard_unmounted_or_denied_message);
                break;
            case ERROR_FILE_CREATES_FAILED:
                title = getString(R.string.check_sdcard_file_create_error_title);
                message = getString(R.string.check_sdcard_file_create_error_message);
                break;
        }

        if (ret != EXTERNAL_CHECK_OK) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setCancelable(false)
                    .setNegativeButton("ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            MainActivity.this.finish();
                        }
                    });
            builder.create().show();
        }
    }

    public File getAlbumStorageDir(String albumName) {
        // Get the directory for the user's public pictures directory.
        File file = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), albumName);
        if (!file.mkdirs()) {
            Log.e(TAG, "Directory not created");
        }
        return file;
    }

    public void saveBitmapToJPG(Bitmap bitmap, File photo) throws IOException {
        Bitmap newBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(newBitmap);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0, 0, null);
        OutputStream stream = new FileOutputStream(photo);
        newBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream);
        stream.close();
    }

    public boolean addJpgSignatureToGallery(Bitmap signature) {
        boolean result = false;
        try {
            File photo = new File(getAlbumStorageDir("SignaturePad"),
                    mCurrentUserID + "_" + (mCurrentSampleID - 1) + ".jpg");
            saveBitmapToJPG(signature, photo);
            scanMediaFile(photo);
            result = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    private void scanMediaFile(File photo) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(photo);
        mediaScanIntent.setData(contentUri);
        MainActivity.this.sendBroadcast(mediaScanIntent);
    }

    public boolean addSvgSignatureToGallery(String signatureSvg) {
        boolean result = false;
        try {
            File svgFile = new File(getAlbumStorageDir("SignaturePad"), String.format("Signature_%d.svg", System.currentTimeMillis()));
            OutputStream stream = new FileOutputStream(svgFile);
            OutputStreamWriter writer  = new OutputStreamWriter(stream);
            writer.write(signatureSvg);
            writer.close();
            stream.flush();
            stream.close();
            scanMediaFile(svgFile);
            result = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    public void onOptionClicked(View view) {

        if (popupMenu == null) {
            popupMenu = new PopupMenu(this, view);
            getMenuInflater().inflate(R.menu.popup_menu, popupMenu.getMenu());
            popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(final MenuItem item) {

                    switch (item.getItemId()) {
                        case R.id.new_user:
                            final View v = getLayoutInflater().inflate(R.layout.option_item_dialog, null);
                            AlertDialog.Builder newUserBuilder = new AlertDialog.Builder(MainActivity.this);
                            newUserBuilder.setIcon(android.R.drawable.ic_dialog_info);
                            newUserBuilder.setView(v);
                            newUserBuilder.setTitle(R.string.new_user_dialog_title);
                            newUserBuilder.setPositiveButton("ok", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    EditText editText = (EditText) v.findViewById(R.id.username);
                                    String username = editText.getText().toString();
                                    if (username != null && username.length() != 0) {

                                        // save this user to database.
                                        mDatabase.insertData(mNextUserId, username, 0);

                                        // save current username.
                                        mCurrentUsername = username;
                                        mUserEditor.putString(CURRENT_USERNAME_KEY, mCurrentUsername);

                                        // save current user id.
                                        mCurrentUserID = mNextUserId;
                                        mUserEditor.putInt(CURRENT_USER_ID_KEY, mCurrentUserID);

                                        // current sample id is zero for new user.
                                        mCurrentSampleID = 0;
                                        mUserEditor.putInt(CURRENT_USER_SAMPLEID_KEY, mCurrentSampleID);

                                        // increase next user id and save id.
                                        mNextUserId++;
                                        mUserEditor.putInt(NEXT_USER_ID_KEY, mNextUserId);

                                        // commit to persist.
                                        mUserEditor.commit();

                                        // update Description
                                        mSignaturepadDescription.setText(getString(R.string.hint_info) +
                                                mCurrentUsername + ":" +  mCurrentUserID + ":" + mCurrentSampleID);
                                    } else {
                                        Toast.makeText(MainActivity.this, "亲，用户名不能为空哦!", Toast.LENGTH_SHORT).show();
                                    }

                                    dialog.dismiss();

                                    fullScreenDisplay();
                                }
                            });
                            newUserBuilder.create().show();
                            break;
                        case R.id.switch_user:
                            AlertDialog.Builder switchUserbuilder = new AlertDialog.Builder(MainActivity.this);
                            switchUserbuilder.setTitle(R.string.option_switch_user_dialog_title);
                            switchUserbuilder.setIcon(android.R.drawable.ic_dialog_info);
                            switchUserbuilder.setCancelable(false);
                            final Cursor cursor = mDatabase.searchUserByID(0);
                            switchUserbuilder.setSingleChoiceItems(new SimpleCursorAdapter(MainActivity.this,
                                            R.layout.userid_name,
                                            cursor,
                                            new String[]{"userid", "username"},
                                            new int[]{R.id.userid, R.id.username},
                                            CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER), -1, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            // move to target.
                                            cursor.moveToPosition(which);

                                            // save current user id.
                                            mCurrentUserID = cursor.getInt(1);
                                            mUserEditor.putInt(CURRENT_USER_ID_KEY, mCurrentUserID);

                                            // save current user name.
                                            mCurrentUsername = cursor.getString(2);
                                            mUserEditor.putString(CURRENT_USERNAME_KEY, mCurrentUsername);

                                            // save current user sample id.
                                            mCurrentSampleID = cursor.getInt(3);
                                            mUserEditor.putInt(CURRENT_USER_SAMPLEID_KEY, mCurrentSampleID);

                                            // commit to persist.
                                            mUserEditor.commit();

                                            // update Description
                                            mSignaturepadDescription.setText(getString(R.string.hint_info) +
                                                    mCurrentUsername + ":" +  mCurrentUserID + ":" + mCurrentSampleID);

                                            dialog.dismiss();

                                            fullScreenDisplay();
                                        }
                                    });
                            switchUserbuilder.setNegativeButton("cancel", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                    fullScreenDisplay();
                                }
                            });
                            switchUserbuilder.create().show();
                            break;
                        default:
                            setPaintColor(item);
                            break;
                    }

                    return true;
                }
            });
        }

        // Reflect to invoke setForceShowIcon function to show menu icon.
        // we may get IllegalAccessException: access to field not allowed here,
        // but it's ok, we just catch it and ignore it.
        try {
            Field[] fields = popupMenu.getClass().getDeclaredFields();
            for (Field field : fields) {
                if ("mPopup".equals(field.getName())) {
                    field.setAccessible(true);
                    Object menuPopupHelper = field.get(popupMenu);
                    Class<?> classPopupHelper = Class.forName(menuPopupHelper
                            .getClass().getName());
                    Method setForceIcons = classPopupHelper.getMethod(
                            "setForceShowIcon", boolean.class);
                    setForceIcons.invoke(menuPopupHelper, true);
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        popupMenu.show();
    }

    private void setPaintColor(MenuItem item) {
        item.setChecked(true);
        switch (item.getItemId()) {
            case R.id.paint_color_black:
                mSignaturePad.setPaintColor(Color.BLACK);
                break;
            case R.id.paint_color_blue:
                mSignaturePad.setPaintColor(Color.BLUE);
                break;
            case R.id.paint_color_cyan:
                mSignaturePad.setPaintColor(Color.CYAN);
                break;
            case R.id.paint_color_dkgray:
                mSignaturePad.setPaintColor(Color.DKGRAY);
                break;
            case R.id.paint_color_gray:
                mSignaturePad.setPaintColor(Color.GRAY);
                break;
            case R.id.paint_color_green:
                mSignaturePad.setPaintColor(Color.GREEN);
                break;
            case R.id.paint_color_ltgray:
                mSignaturePad.setPaintColor(Color.LTGRAY);
                break;
            case R.id.paint_color_magenta:
                mSignaturePad.setPaintColor(Color.MAGENTA);
                break;
            case R.id.paint_color_red:
                mSignaturePad.setPaintColor(Color.RED);
                break;
            case R.id.paint_color_yellow:
                mSignaturePad.setPaintColor(Color.YELLOW);
                break;
        }
        fullScreenDisplay();
    }

    private int checkExternalFile() {

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return ERROR_SDCARD_UNMOUNTED_OR_DENIED;
        }

        File sdcardDir = Environment.getExternalStorageDirectory();

        try {
            File records = new File(sdcardDir.getCanonicalPath(), RECORDS_FILE_NAME);
            boolean isFileOk;
            if (!records.exists()) {
                isFileOk = records.createNewFile();
            } else {
                isFileOk = true;
            }

            if (isFileOk) {
                mRecordsFileOutStream = new FileOutputStream(records, true);
            } else {
                return ERROR_FILE_CREATES_FAILED;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return ERROR_FILE_CREATES_FAILED;
        }

        return EXTERNAL_CHECK_OK;
    }

    private void fullScreenDisplay (){
        if (mSignaturePad != null) {
            // full screen setting, make our sign UI fullscreen.
            mSignaturePad.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private class WriteRecordsThread extends Thread {

        private SparseArray<SignaturePad.MotionEventRecorder> records = null;
        private int isTrue = 0;

        public WriteRecordsThread(SparseArray<SignaturePad.MotionEventRecorder> records, int isTrue) {
            this.records = records;
            this.isTrue = isTrue;
        }

        @Override
        public void run() {
            super.run();

            Looper.prepare();

            if (records == null || records.size() == 0) {
                return;
            }

            try {
                // write tags
                String tag = SIGNATUREID_TAG + " " + mCurrentUserID + "_" + mCurrentSampleID + "\n";
                mRecordsFileOutStream.write(tag.getBytes());
                tag = SIGNATURELABLE_TAG + " " + isTrue + "\n";
                mRecordsFileOutStream.write(tag.getBytes());


                for (int i = 0; i < records.size(); i++) {
                    SignaturePad.MotionEventRecorder recorder = records.get(i, null);
                    if (recorder != null) {
                        // save to record file.
                        mRecordsFileOutStream.write(recorder.toString().getBytes());
                        mRecordsFileOutStream.write('\n');
                    }
                }

                // add a new line
                mRecordsFileOutStream.write('\n');

                // add sample id
                mCurrentSampleID++;
                mUserEditor.putInt(CURRENT_USER_SAMPLEID_KEY, mCurrentSampleID);
                mUserEditor.commit();

                // update sample id info of description.
                mWriteEventHandler.obtainMessage(MSG_WRITE_COMPLETE).sendToTarget();

                // save a picture of this.
                Bitmap signatureBitmap = mSignaturePad.getSignatureBitmap();
                if(addJpgSignatureToGallery(signatureBitmap)) {
                    Toast.makeText(MainActivity.this, "Signature saved complete!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Unable to store the signature", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // clear records anyway.
            records.clear();

            Looper.loop();
        }
    }
}
