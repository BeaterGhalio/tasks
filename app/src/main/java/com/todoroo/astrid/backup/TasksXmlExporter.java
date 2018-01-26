/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.backup;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Xml;
import android.widget.Toast;

import com.todoroo.andlib.data.AbstractModel;
import com.todoroo.andlib.data.Property;
import com.todoroo.andlib.data.Property.PropertyVisitor;
import com.todoroo.andlib.sql.Order;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.DialogUtilities;
import org.tasks.data.TagDataDao;
import com.todoroo.astrid.dao.TaskDao;
import org.tasks.data.UserActivityDao;
import org.tasks.data.TagData;
import com.todoroo.astrid.data.Task;
import org.tasks.data.UserActivity;

import org.tasks.R;
import org.tasks.backup.XmlWriter;
import org.tasks.data.Alarm;
import org.tasks.data.AlarmDao;
import org.tasks.data.GoogleTask;
import org.tasks.data.GoogleTaskDao;
import org.tasks.data.Location;
import org.tasks.data.LocationDao;
import org.tasks.data.Tag;
import org.tasks.data.TagDao;
import org.tasks.preferences.Preferences;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

import timber.log.Timber;

import static org.tasks.date.DateTimeUtils.newDateTime;

public class TasksXmlExporter {

    public static final String PREF_BACKUP_LAST_DATE = "backupDate"; //$NON-NLS-1$

    // --- public interface

    public enum ExportType {
        EXPORT_TYPE_SERVICE,
        EXPORT_TYPE_MANUAL,
        EXPORT_TYPE_ON_UPGRADE
    }

    // --- implementation

    private final TagDataDao tagDataDao;
    private final AlarmDao alarmDao;
    private final LocationDao locationDao;
    private final TagDao tagDao;
    private final GoogleTaskDao googleTaskDao;
    private final TaskDao taskDao;
    private UserActivityDao userActivityDao;
    private final Preferences preferences;

    private static final int FORMAT = 4;
    private Context context;
    private int exportCount = 0;
    private XmlSerializer xml;

    private ProgressDialog progressDialog;
    private Handler handler;
    private File backupDirectory;
    private String latestSetVersionName;

    private void post(Runnable runnable) {
        if (handler != null) {
            handler.post(runnable);
        }
    }

    private void setProgress(final int taskNumber, final int total) {
        post(() -> {
            progressDialog.setMax(total);
            progressDialog.setProgress(taskNumber);
        });
    }

    @Inject
    public TasksXmlExporter(TagDataDao tagDataDao, TaskDao taskDao, UserActivityDao userActivityDao,
                            Preferences preferences, AlarmDao alarmDao, LocationDao locationDao,
                            TagDao tagDao, GoogleTaskDao googleTaskDao) {
        this.tagDataDao = tagDataDao;
        this.taskDao = taskDao;
        this.userActivityDao = userActivityDao;
        this.preferences = preferences;
        this.alarmDao = alarmDao;
        this.locationDao = locationDao;
        this.tagDao = tagDao;
        this.googleTaskDao = googleTaskDao;
    }

    public void exportTasks(final Context context, final ExportType exportType, @Nullable final ProgressDialog progressDialog) {
        this.context = context;
        this.exportCount = 0;
        this.backupDirectory = preferences.getBackupDirectory();
        this.latestSetVersionName = null;
        this.progressDialog = progressDialog;

        handler = exportType == ExportType.EXPORT_TYPE_MANUAL ? new Handler() : null;

        new Thread(() -> {
            try {
                String output = setupFile(backupDirectory,
                        exportType);
                int tasks = taskDao.count(Query.select());

                if(tasks > 0) {
                    doTasksExport(output);
                }

                preferences.setLong(PREF_BACKUP_LAST_DATE, DateUtilities.now());

                if (exportType == ExportType.EXPORT_TYPE_MANUAL) {
                    onFinishExport(output);
                }
            } catch (IOException e) {
                Timber.e(e, e.getMessage());
            } finally {
                post(() -> {
                    if(progressDialog != null && progressDialog.isShowing() && context instanceof Activity) {
                        DialogUtilities.dismissDialog((Activity) context, progressDialog);
                    }
                });
            }
        }).start();
    }

    private void doTasksExport(String output) throws IOException {
        File xmlFile = new File(output);
        xmlFile.createNewFile();
        FileOutputStream fos = new FileOutputStream(xmlFile);
        xml = Xml.newSerializer();
        xml.setOutput(fos, BackupConstants.XML_ENCODING);

        xml.startDocument(null, null);
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

        xml.startTag(null, BackupConstants.ASTRID_TAG);
        xml.attribute(null, BackupConstants.ASTRID_ATTR_VERSION,
                Integer.toString(preferences.getLastSetVersion()));
        xml.attribute(null, BackupConstants.ASTRID_ATTR_FORMAT,
                Integer.toString(FORMAT));

        serializeTasks();
        serializeTagDatas();

        xml.endTag(null, BackupConstants.ASTRID_TAG);
        xml.endDocument();
        xml.flush();
        fos.close();
    }

    private void serializeTagDatas() {
        for (TagData tag : tagDataDao.allTags()) {
            try {
                xml.startTag(null, BackupConstants.TAGDATA_TAG);
                tag.writeToXml(new XmlWriter(xml));
                xml.endTag(null, BackupConstants.TAGDATA_TAG);
            } catch(IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void serializeTasks() throws IOException {
        List<Task> tasks = taskDao.toList(Query.select().orderBy(Order.asc(Task.ID)));
        int length = tasks.size();
        for(int i = 0; i < length; i++) {
            Task task = tasks.get(i);

            setProgress(i, length);

            xml.startTag(null, BackupConstants.TASK_TAG);
            serializeTask(task);
            xml.endTag(null, BackupConstants.TASK_TAG);
            this.exportCount++;
        }
    }

    private synchronized void serializeTask(Task task) {
        XmlWriter writer = new XmlWriter(xml);
        task.writeToXml(writer);
        for (Alarm alarm : alarmDao.getAlarms(task.getId())) {
            try {
                xml.startTag(null, BackupConstants.ALARM_TAG);
                alarm.writeToXml(writer);
                xml.endTag(null, BackupConstants.ALARM_TAG);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        for (Tag tag : tagDao.getTagsForTask(task.getId())) {
            try {
                xml.startTag(null, BackupConstants.TAG_TAG);
                tag.writeToXml(writer);
                xml.endTag(null, BackupConstants.TAG_TAG);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        for (Location location : locationDao.getGeofences(task.getId())) {
            try {
                xml.startTag(null, BackupConstants.LOCATION_TAG);
                location.writeToXml(writer);
                xml.endTag(null, BackupConstants.LOCATION_TAG);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        for (GoogleTask googleTask : googleTaskDao.getAllByTaskId(task.getId())) {
            try {
                xml.startTag(null, BackupConstants.GOOGLE_TASKS_TAG);
                googleTask.writeToXml(writer);
                xml.endTag(null, BackupConstants.GOOGLE_TASKS_TAG);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        for (UserActivity comment : userActivityDao.getCommentsForTask(task.getUuid())) {
            try {
                xml.startTag(null, BackupConstants.COMMENT_TAG);
                comment.writeToXml(new XmlWriter(xml));
                xml.endTag(null, BackupConstants.COMMENT_TAG);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final String XML_NULL = "null"; //$NON-NLS-1$

    private void onFinishExport(final String outputFile) {
        post(() -> {
            if(exportCount == 0) {
                Toast.makeText(context, context.getString(R.string.export_toast_no_tasks), Toast.LENGTH_LONG).show();
            } else {
                CharSequence text = String.format(context.getString(R.string.export_toast),
                        context.getResources().getQuantityString(R.plurals.Ntasks, exportCount,
                                exportCount), outputFile);
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Creates directories if necessary and returns fully qualified file
     * @return output file name
     * @throws IOException
     */
    private String setupFile(File directory, ExportType exportType) throws IOException {
        if (directory != null) {
            // Check for /sdcard/astrid directory. If it doesn't exist, make it.
            if (directory.exists() || directory.mkdir()) {
                String fileName;
                switch(exportType) {
                case EXPORT_TYPE_SERVICE:
                    fileName = String.format(BackupConstants.BACKUP_FILE_NAME, getDateForExport());
                    break;
                case EXPORT_TYPE_MANUAL:
                    fileName = String.format(BackupConstants.EXPORT_FILE_NAME, getDateForExport());
                    break;
                case EXPORT_TYPE_ON_UPGRADE:
                    fileName = String.format(BackupConstants.UPGRADE_FILE_NAME, latestSetVersionName);
                    break;
                default:
                     throw new IllegalArgumentException("Invalid export type"); //$NON-NLS-1$
                }
                return directory.getAbsolutePath() + File.separator + fileName;
            } else {
                // Unable to make the /sdcard/astrid directory.
                throw new IOException(context.getString(R.string.DLG_error_sdcard,
                        directory.getAbsolutePath()));
            }
        } else {
            // Unable to access the sdcard because it's not in the mounted state.
            throw new IOException(context.getString(R.string.DLG_error_sdcard_general));
        }
    }

    private static String getDateForExport() {
        return newDateTime().toString("yyMMdd-HHmm");
    }
}
