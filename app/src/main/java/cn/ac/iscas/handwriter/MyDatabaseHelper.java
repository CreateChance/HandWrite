package cn.ac.iscas.handwriter;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * Database helper class.
 */
public class MyDatabaseHelper extends SQLiteOpenHelper {

    private final String TAG = "MyDatabaseHelper";

    final String CREATE_USERLIST_TABLE_SQL = "create table userlist(_id integer primary " +
            "key autoincrement , userid , username, sampleid)";

    public MyDatabaseHelper(Context context, String name, int version) {
        super(context, name, null, version);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_USERLIST_TABLE_SQL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "update database from old version: " +
                oldVersion + " to new version: " + newVersion);
    }
}
