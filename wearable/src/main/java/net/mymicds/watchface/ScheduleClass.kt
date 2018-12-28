package net.mymicds.watchface

import android.graphics.Color
import android.support.annotation.ColorInt
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class ScheduleClass(val name: String, val start: LocalTime, val end: LocalTime, @ColorInt val color: Int) {
    companion object {
        fun fromJSON(json: JSONObject): ScheduleClass {
            val classObj = json.getJSONObject("class")
            return ScheduleClass(
                classObj.getString("name"),
                ZonedDateTime.parse(json.getString("start")).withZoneSameInstant(ZoneId.systemDefault()).toLocalTime(),
                ZonedDateTime.parse(json.getString("end")).withZoneSameInstant(ZoneId.systemDefault()).toLocalTime(),
                Color.parseColor(classObj.getString("color"))
            )
        }
    }
}
