package com.kayx.manager.ui

import java.text.DateFormat
import java.util.Date

private val dateTimeFmt: ThreadLocal<DateFormat> = ThreadLocal.withInitial {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
}

/** "17 Nov 2026, 5:30 am" – date *and* time in the user's locale, used under file/folder names next to the size. */
fun formatDateTime(millis: Long): String = (dateTimeFmt.get() ?: DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)).format(Date(millis))
