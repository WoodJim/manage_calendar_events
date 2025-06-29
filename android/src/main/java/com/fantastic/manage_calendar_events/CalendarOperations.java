package com.fantastic.manage_calendar_events;

import android.Manifest.permission;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.util.Log;

import com.fantastic.manage_calendar_events.models.Calendar;
import com.fantastic.manage_calendar_events.models.CalendarEvent;
import com.fantastic.manage_calendar_events.models.CalendarEvent.Reminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.time.ZoneId;
import java.time.LocalDateTime;

public class CalendarOperations {

    private static final int MY_CAL_REQ = 101;
    private static final int MY_CAL_WRITE_REQ = 102;

    private static final String[] EVENT_PROJECTION =
            {
                    CalendarContract.Instances._ID,
                    Events.TITLE,
                    Events.DESCRIPTION,
                    Events.EVENT_LOCATION,
                    Events.CUSTOM_APP_URI,
                    Events.DTSTART,
                    Events.DTEND,
                    Events.ALL_DAY,
                    Events.DURATION,
                    Events.HAS_ALARM,
                    Events.RRULE,
            };

    private Context ctx;
    private Activity activity;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public CalendarOperations(Activity activity, Context ctx) {
        this.activity = activity; this.ctx = ctx;
    }


    boolean hasPermissions() {
        if (23 <= android.os.Build.VERSION.SDK_INT && activity != null) {
            boolean writeCalendarPermissionGranted =
                    ctx.checkSelfPermission(permission.WRITE_CALENDAR)
                            == PackageManager.PERMISSION_GRANTED;
            boolean readCalendarPermissionGranted =
                    ctx.checkSelfPermission(permission.READ_CALENDAR)
                            == PackageManager.PERMISSION_GRANTED;

            return writeCalendarPermissionGranted && readCalendarPermissionGranted;
        }

        return true;
    }

    void requestPermissions() {
        if (23 <= android.os.Build.VERSION.SDK_INT && activity != null) {
            String[] permissions = new String[]{permission.WRITE_CALENDAR,
                    permission.READ_CALENDAR};
            activity.requestPermissions(permissions, MY_CAL_REQ);
        }
    }

    public ArrayList<Calendar> getCalendars() {
        ContentResolver cr = ctx.getContentResolver();
        ArrayList<Calendar> calendarList = new ArrayList<>();

        String[] mProjection =
                {
                        Calendars._ID,
                        Calendars.ACCOUNT_NAME,
                        Calendars.CALENDAR_DISPLAY_NAME,
                        Calendars.OWNER_ACCOUNT,
                        Calendars.CALENDAR_ACCESS_LEVEL
                };

        Uri uri = Calendars.CONTENT_URI;

        if (!hasPermissions()) {
            requestPermissions();
        }
        Cursor cur = cr.query(uri, mProjection, null, null, null);

        try {
            while (cur.moveToNext()) {
                String calenderId = cur.getLong(cur.getColumnIndex(Calendars._ID)) + "";
                String displayName = cur
                        .getString(cur.getColumnIndex(Calendars.CALENDAR_DISPLAY_NAME));
                String accountName = cur
                        .getString(cur.getColumnIndex(Calendars.ACCOUNT_NAME));
                String ownerName = cur
                        .getString(cur.getColumnIndex(Calendars.OWNER_ACCOUNT));
                Calendar calendar = new Calendar(calenderId, displayName, accountName, ownerName);
                calendarList.add(calendar);
            }
        } catch (Exception e) {
            Log.e("XXX", e.getMessage());
        } finally {
            cur.close();
        }
        return calendarList;
    }

    public ArrayList<CalendarEvent> getAllEvents(String calendarId) {
        String selection =
                Events.CALENDAR_ID + " = " + calendarId + " AND " + Events.DELETED + " != 1";
        return getEvents(selection, null, null);
    }

    public ArrayList<CalendarEvent> getEventsByDateRange(String calendarId, long startDate, long endDate) {
        // 扩展查询条件以包含周期性事件
        String selection = Events.CALENDAR_ID + " = " + calendarId
                + " AND " + Events.DELETED + " != 1"
                + " AND ("
                + "((" + Events.DTSTART + " <= " + endDate + ")"    // 事件开始时间在查询结束时间之前
                + " AND ("
                + Events.DTEND + " >= " + startDate + ")"       // 事件结束时间在查询开始时间之后
                + ")"
                + " OR (" + Events.RRULE + " IS NOT NULL"           // 包含重复事件规则
                + " AND " + Events.DTSTART + " <= " + endDate + ")" // 重复事件开始时间早于查询结束时间
                + ")";

        // 添加调试日志
        Log.d("CalendarDebug", "Querying events with condition: " + selection);
        return getEvents(selection, startDate, endDate);
    }

    /**
     * Return all the events from calendar which satisfies the given query selection
     *
     * @param selection - Conditions to filter the calendar events
     * @return List of Calendar events
     */
    public ArrayList<CalendarEvent> getEvents(String selection, Long queryStart, Long queryEnd) {
        if (!hasPermissions()) {
            requestPermissions();
            return new ArrayList<>(); // 如果没有权限，返回空列表
        }

        ContentResolver cr = ctx.getContentResolver();
        ArrayList<CalendarEvent> calendarEvents = new ArrayList<>();

        Uri uri = Events.CONTENT_URI;
        String eventsSortOrder = Events.DTSTART + " ASC";

        // 查询事件
        Cursor cur = cr.query(uri, EVENT_PROJECTION, selection, null, eventsSortOrder);

        if (cur == null) {
            Log.e("CursorError", "Cursor is null");
            return calendarEvents;
        }

        try {
            if (cur.moveToFirst()) {
                do {
                    // 确保列名正确
                    int eventIdIndex = cur.getColumnIndex(Events._ID);
                    int titleIndex = cur.getColumnIndex(Events.TITLE);
                    int descIndex = cur.getColumnIndex(Events.DESCRIPTION);
                    int locationIndex = cur.getColumnIndex(Events.EVENT_LOCATION);
                    int urlIndex = cur.getColumnIndex(Events.CUSTOM_APP_URI);
                    int startDateIndex = cur.getColumnIndex(Events.DTSTART);
                    int endDateIndex = cur.getColumnIndex(Events.DTEND);
                    int rRuleIndex = cur.getColumnIndex(Events.RRULE);
                    int durationIndex = cur.getColumnIndex(Events.DURATION);
                    int allDayIndex = cur.getColumnIndex(Events.ALL_DAY);
                    int hasAlarmIndex = cur.getColumnIndex(Events.HAS_ALARM);

                    // 检查列是否存在
                    if (eventIdIndex == -1 || titleIndex == -1 || startDateIndex == -1 || endDateIndex == -1) {
                        Log.e("CursorError", "Required columns not found");
                        continue;
                    }

                    String eventId = cur.getString(eventIdIndex);
                    String title = cur.getString(titleIndex);
                    String desc = descIndex != -1 ? cur.getString(descIndex) : null;
                    String location = locationIndex != -1 ? cur.getString(locationIndex) : null;
                    String url = urlIndex != -1 ? cur.getString(urlIndex) : null;
                    String rRule = rRuleIndex != -1 ? cur.getString(rRuleIndex) : null;
                    long startDate = cur.getLong(startDateIndex);
                    long endDate = endDateIndex != -1 ? cur.getLong(endDateIndex) : 0;
                    long duration = durationIndex != -1 ? cur.getLong(durationIndex) : 0;

                    // 关键修改：如果结束时间为 0，根据持续时间计算
                    if (endDate == 0 && duration > 0) {
                        endDate = startDate + duration;
                    }

                    boolean isAllDay = allDayIndex != -1 && cur.getInt(allDayIndex) > 0;
                    boolean hasAlarm = hasAlarmIndex != -1 && cur.getInt(hasAlarmIndex) > 0;

                    // 在读取 rRule 后，添加以下逻辑
                    if (rRule != null && !rRule.isEmpty()) {
                        long start = queryStart != null ? queryStart :
                                LocalDateTime.now().minusMonths(6)
                                        .withHour(0).withMinute(0).withSecond(0)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant().toEpochMilli();

                        long end = queryEnd != null ? queryEnd :
                                LocalDateTime.now().plusMonths(6)
                                        .withHour(23).withMinute(59).withSecond(59)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant().toEpochMilli();

                        List<CalendarEvent> recurringInstances = getRecurringEventInstances(
                                eventId, start, end, rRule, title, desc, location, url, isAllDay, hasAlarm
                        );
                        calendarEvents.addAll(recurringInstances);
                    } else {
                        // 非周期性事件直接添加
                        CalendarEvent event = new CalendarEvent(
                                eventId, title, desc, startDate, endDate, location, url, isAllDay, hasAlarm
                        );
                        calendarEvents.add(event);
                    }
                } while (cur.moveToNext());
            } else {
                Log.e("CursorError", "Cursor is empty");
            }
        } catch (Exception e) {
            Log.e("XXX", e.getMessage());
        } finally {
            cur.close();
        }

        updateRemindersAndAttendees(calendarEvents);
        return calendarEvents;
    }

    private CalendarEvent getEvent(String calendarId, String eventId) {
        if (!hasPermissions()) {
            requestPermissions();
        }
        String selection =
                Events.CALENDAR_ID + " = " + calendarId + " AND " + CalendarContract.Instances._ID
                        + " = " + eventId;

        ArrayList<CalendarEvent> events = getEvents(selection, null, null);
        assert (events.size() == 1);
        return events.get(0);
    }

    public void createUpdateEvent(String calendarId, CalendarEvent event) {
        if (!hasPermissions()) {
            requestPermissions();
        }

        ContentResolver cr = ctx.getContentResolver();

        String currentTimeZone = java.util.Calendar.getInstance().getTimeZone().getDisplayName();
        String eventId = event.getEventId() != null ? event.getEventId() : null;
        ContentValues values = new ContentValues();
        values.put(Events.DTSTART, event.getStartDate());
        values.put(Events.DTEND, event.getEndDate());
        values.put(Events.TITLE, event.getTitle());
        values.put(Events.DESCRIPTION, event.getDescription());
        values.put(Events.CALENDAR_ID, calendarId);
        values.put(Events.EVENT_TIMEZONE, currentTimeZone);
        values.put(Events.ALL_DAY, event.isAllDay());
        values.put(Events.HAS_ALARM, event.isHasAlarm());
        if (event.getLocation() != null) {
            values.put(Events.EVENT_LOCATION, event.getLocation());
        }
        if (event.getUrl() != null) {
            values.put(Events.CUSTOM_APP_URI, event.getUrl());
        }

        try {
            if (eventId == null) {
                Uri uri = cr.insert(Events.CONTENT_URI, values);
                // get the event ID that is the last element in the Uri
                eventId = Long.parseLong(uri.getLastPathSegment()) + "";
                event.setEventId(eventId);
            } else {
                String selection =
                        Events.CALENDAR_ID + " = " + calendarId + " AND " + CalendarContract.Instances._ID
                                + " = " + eventId;
                int updCount = cr.update(Events.CONTENT_URI, values, selection,
                        null);
            }
        } catch (Exception e) {
            Log.e("XXX", e.getMessage());
        }
    }

    public boolean deleteEvent(String calendarId, String eventId) {
        if (!hasPermissions()) {
            requestPermissions();
        }
        Uri uri = Events.CONTENT_URI;
        String selection =
                Events.CALENDAR_ID + " = " + calendarId + " AND " + CalendarContract.Instances._ID
                        + " = " + eventId;

        int updCount = ctx.getContentResolver().delete(uri, selection, null);
        return updCount != 0;
    }

    private void updateRemindersAndAttendees(ArrayList<CalendarEvent> events) {
        for (CalendarEvent event : events) {
            executor.submit(() -> {
                getReminders(event);
                List<CalendarEvent. Attendee> attendees = getAttendees(event.getEventId());
                new Handler(Looper.getMainLooper()).post(() -> {
                    event.setAttendees(attendees);
                });
            });
        }
    }

    public List<CalendarEvent.Attendee> getAttendees(String eventId) {
        if (!hasPermissions()) {
            requestPermissions();
        }
        ContentResolver cr = ctx.getContentResolver();

        String[] mProjection =
                {
                        CalendarContract.Attendees.EVENT_ID,
                        CalendarContract.Attendees._ID,
                        CalendarContract.Attendees.ATTENDEE_NAME,
                        CalendarContract.Attendees.ATTENDEE_EMAIL,
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                        CalendarContract.Attendees.IS_ORGANIZER,
                };

        Uri uri = CalendarContract.Attendees.CONTENT_URI;
        String selection = CalendarContract.Attendees.EVENT_ID + " = " + eventId;

        Cursor cur = cr.query(uri, mProjection, selection, null, null);
        int cursorSize = cur.getCount();

        Set<CalendarEvent.Attendee> attendees = new HashSet<>();

        CalendarEvent.Attendee organiser = null;
        try {
            while (cur.moveToNext()) {
                String attendeeId =
                        cur.getLong(cur.getColumnIndex(CalendarContract.Attendees._ID)) + "";
                String name =
                        cur.getString(cur.getColumnIndex(CalendarContract.Attendees.ATTENDEE_NAME));
                String emailAddress =
                        cur.getString(cur.getColumnIndex(CalendarContract.Attendees.ATTENDEE_EMAIL));
                int relationship = cur
                        .getInt(cur.getColumnIndex(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP));

                boolean isOrganiser =
                        relationship == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER;

                if (name.isEmpty() && !emailAddress.isEmpty()) {
                    name = capitalize(emailAddress.replaceAll("((@.*)|[^a-zA-Z])+", " ").trim());
                }
                CalendarEvent.Attendee attendee = new CalendarEvent.Attendee(attendeeId, name,
                        emailAddress, isOrganiser);

                if (isOrganiser) {
                    organiser = attendee;
                } else {
                    attendees.add(attendee);
                }
            }
        } catch (Exception e) {
            Log.e("XXX", e.getMessage());
        } finally {
            cur.close();
        }
        ArrayList<CalendarEvent.Attendee> attendeeList = new ArrayList<>(attendees);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Collections.sort(attendeeList, Comparator.comparing(CalendarEvent.Attendee::getEmailAddress));
        }
        if (organiser != null && !attendeeList.isEmpty())
            attendeeList.add(0, organiser);

        if (cursorSize != attendeeList.size()) {
            deleteAllAttendees(eventId);
            addAttendees(eventId, attendeeList);
        }
        return attendeeList;
    }

    private String capitalize(final String line) {
        return Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    public void addAttendees(String eventId,
                             List<CalendarEvent.Attendee> attendees) {
        if (!hasPermissions()) {
            requestPermissions();
        }
        if (attendees.isEmpty()) {
            return;
        }

        ContentResolver cr = ctx.getContentResolver();
        ContentValues[] valuesArray = new ContentValues[attendees.size()];

        for (int i = 0, attendeesSize = attendees.size(); i < attendeesSize; i++) {
            ContentValues values = getContentValues(eventId, attendees, i);
            valuesArray[i] = values;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            cr.bulkInsert(CalendarContract.Attendees.CONTENT_URI, valuesArray);
        });
    }

    private static ContentValues getContentValues(String eventId, List<CalendarEvent.Attendee> attendees, int i) {
        CalendarEvent.Attendee attendee = attendees.get(i);
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Attendees.EVENT_ID, eventId);
        values.put(CalendarContract.Attendees.ATTENDEE_NAME, attendee.getName());
        values.put(CalendarContract.Attendees.ATTENDEE_EMAIL, attendee.getEmailAddress());
        values.put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                attendee.isOrganiser() ? CalendarContract.Attendees.RELATIONSHIP_ORGANIZER :
                        CalendarContract.Attendees.RELATIONSHIP_ATTENDEE);
        return values;
    }

    public int deleteAttendee(String eventId,
                              CalendarEvent.Attendee attendee) {
        if (!hasPermissions()) {
            requestPermissions();
        }

        Uri uri = CalendarContract.Attendees.CONTENT_URI;
        String selection =
                CalendarContract.Attendees.EVENT_ID + " = " + eventId
                        + " AND " + CalendarContract.Attendees.ATTENDEE_EMAIL
                        + " = '" + attendee.getEmailAddress() + "'";

        return ctx.getContentResolver().delete(uri, selection, null);
    }

    private void deleteAllAttendees(String eventId) {
        if (!hasPermissions()) {
            requestPermissions();
        }
        Uri uri = CalendarContract.Attendees.CONTENT_URI;
        String selection = CalendarContract.Attendees.EVENT_ID + " = " + eventId;
        ctx.getContentResolver().delete(uri, selection, null);
    }

    private void getReminders(CalendarEvent event) {
        String eventId = event.getEventId();
        if (!hasPermissions()) {
            requestPermissions();
        }
        ContentResolver cr = ctx.getContentResolver();

        String[] mProjection =
                {
                        CalendarContract.Reminders.EVENT_ID,
                        CalendarContract.Reminders.METHOD,
                        CalendarContract.Reminders.MINUTES,
                };

        Uri uri = CalendarContract.Reminders.CONTENT_URI;
        String selection = CalendarContract.Reminders.EVENT_ID + " = " + eventId;
//        String[] selectionArgs = new String[]{"2"};

        Cursor cur = cr.query(uri, mProjection, selection, null, null);

        try {
            while (cur.moveToNext()) {
                long minutes = cur.getLong(cur.getColumnIndex(CalendarContract.Reminders.MINUTES));
                Reminder reminder = new CalendarEvent.Reminder(minutes);
                new Handler(Looper.getMainLooper()).post(() -> {
                    event.setReminder(reminder);
                });
            }
        } catch (Exception e) {
            Log.e("XXX", e.getMessage());
        } finally {
            cur.close();
        }
    }

    private List<CalendarEvent> getRecurringEventInstances(
            String eventId, long queryStart, long queryEnd, String rRuleStr,
            String title, String desc, String location, String url,
            boolean isAllDay, boolean hasAlarm) {
        List<CalendarEvent> events = new ArrayList<>();
        String[] projection = {
                Instances.EVENT_ID,
                Instances.TITLE,
                Instances.BEGIN,
                Instances.END,
                Instances.EVENT_LOCATION,
                Instances.ALL_DAY
        };
        Uri uri = Instances.CONTENT_URI
                .buildUpon()
                .appendPath(String.valueOf(queryStart))
                .appendPath(String.valueOf(queryEnd))
                .build();

        // 修改查询条件（添加EVENT_ID过滤）
        String selection = Instances.EVENT_ID + " = ? AND "
                + Events.DELETED + " != 1";
        String[] selectionArgs = new String[] {
                eventId
        };

        ContentResolver contentResolver = ctx.getContentResolver();

        Cursor cur = contentResolver.query(
                uri,  // 使用修正后的URI
                projection,
                selection,
                selectionArgs,
                Instances.BEGIN + " ASC"
        );

        if (cur != null) {
            while (cur.moveToNext()) {
                int eventIdIndex = cur.getColumnIndex(Instances.EVENT_ID);
                int beginIndex = cur.getColumnIndex(Instances.BEGIN);
                int endIndex = cur.getColumnIndex(Instances.END);

                    long instanceStart = cur.getLong(cur.getColumnIndex(Instances.BEGIN));
                    long instanceEnd = cur.getLong(cur.getColumnIndex(Instances.END));
//                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//                    sdf.setTimeZone(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
//                    String startStr = sdf.format(new Date(instanceStart));
//                    String endStr = sdf.format(new Date(instanceEnd));
//                    Log.d("CalendarDebug", "eventId: " + eventId + " title: " + title + " startDate: " + startStr + " endDate: " + endStr);
                    CalendarEvent event = new CalendarEvent(
                            eventId, title, desc, instanceStart, instanceEnd, location, url, isAllDay, hasAlarm
                    );
                    events.add(event);

            }
            cur.close();
        }
        return events;
    }

    public void addReminder(String calendarId, String eventId, long minutes) {
        if (!hasPermissions()) {
            requestPermissions();
        }

        CalendarEvent event = getEvent(calendarId, eventId);

        ContentResolver cr = ctx.getContentResolver();
        ContentValues values = new ContentValues();

        values.put(CalendarContract.Reminders.EVENT_ID, event.getEventId());
        values.put(CalendarContract.Reminders.MINUTES, minutes);
        values.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALARM);

        cr.insert(CalendarContract.Reminders.CONTENT_URI, values);

        event.setHasAlarm(true);
    }

    public int updateReminder(String calendarId, String eventId, long minutes) {
        if (!hasPermissions()) {
            requestPermissions();
        }
        CalendarEvent event = getEvent(calendarId, eventId);

        ContentValues contentValues = new ContentValues();
        contentValues.put(CalendarContract.Reminders.MINUTES, minutes);

        Uri uri = CalendarContract.Reminders.CONTENT_URI;

        String selection = CalendarContract.Reminders.EVENT_ID + " = " + event.getEventId();
        int updCount = ctx.getContentResolver()
                .update(uri, contentValues, selection, null);
        return updCount;
    }

    public int deleteReminder(String eventId) {
        if (!hasPermissions()) {
            requestPermissions();
        }

        Uri uri = CalendarContract.Reminders.CONTENT_URI;
        String selection = CalendarContract.Reminders.EVENT_ID + " = " + eventId;

        int updCount = ctx.getContentResolver().delete(uri, selection, null);
        return updCount;
    }

}