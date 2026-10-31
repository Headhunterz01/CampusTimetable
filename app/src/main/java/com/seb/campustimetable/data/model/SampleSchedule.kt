package com.seb.campustimetable.data.model

import android.graphics.Color

object SampleSchedule {
    // Monday 2026-08-31 makes 2026-10-26 the start of week 9 for the example timetable.
    val semesterStart = java.time.LocalDate.of(2026, 8, 31)

    val courses = listOf(
        CourseBlock("labor", "劳动教育 I", "杨钊、杨勋", "楼207", 4, 1, 2, Color.rgb(246, 158, 0), weeks = listOf(9)),
        CourseBlock("inorganic_am", "无机及分析化学", "杨钊、杨勋", "楼209", 4, 3, 4, Color.rgb(208, 157, 210), weeks = listOf(9)),
        CourseBlock("lab_safety", "实验室安全与防护", "杨钊、杨勋", "楼104", 5, 1, 2, Color.rgb(4, 183, 217), weeks = listOf(9)),
        CourseBlock("english", "大学英语 I", "杨钊、杨勋", "楼103", 5, 3, 4, Color.rgb(255, 207, 0), weeks = listOf(9)),
        CourseBlock("inorganic_pm", "无机及分析化学", "杨钊、杨勋", "楼506", 5, 6, 7, Color.rgb(255, 151, 0), weeks = listOf(9)),
        CourseBlock("xi_ideology_fri", "习近平新时代中国特色社会主义思想概论", "杨钊、杨勋", "", 5, 8, 9, Color.rgb(255, 151, 0), weeks = listOf(9)),
    )
}
