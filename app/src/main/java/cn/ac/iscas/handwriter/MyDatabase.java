package cn.ac.iscas.handwriter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

/**
 * Local database class, access data via helper class.
 */
public class MyDatabase {
    private final String TAG = "MyDatabase";

    final String DATABASE_NAME = "handwriter.db3";
    MyDatabaseHelper databaseHelper = null;

    private final String TABLE_NAME = "userlist";
    final String TABLE_USER_ID_COLUMN_NAME = "userid";
    final String TABLE_USERNAME_COLUMN_NAME = "username";
    final String TABLE_SAMPLE_ID_COLUMN_NAME = "sampleid";

    public MyDatabase(Context context) {
        databaseHelper = new MyDatabaseHelper(context, DATABASE_NAME, 1);
    }

    public void insertData(int userid, String username, int sampleid) {
        ContentValues values = new ContentValues();
        values.put(TABLE_USER_ID_COLUMN_NAME, userid);
        values.put(TABLE_USERNAME_COLUMN_NAME, username);
        values.put(TABLE_SAMPLE_ID_COLUMN_NAME, sampleid);
        databaseHelper.getReadableDatabase().insert(TABLE_NAME, null, values);
    }

    public Cursor searchUserByID(int userid) {
        Cursor cursor = null;

        // if user id > 0, means search for one user
        // if user id == 0, means search for all the users.
        if (userid > 0) {
            cursor = databaseHelper.getReadableDatabase().rawQuery(
                    "select * from " + TABLE_NAME + " where userid == " + userid,
                    null);
        } else if (userid == 0) {
            cursor = databaseHelper.getReadableDatabase().rawQuery(
                    "select * from " + TABLE_NAME,
                    null);
        }

        return cursor;
    }
}
