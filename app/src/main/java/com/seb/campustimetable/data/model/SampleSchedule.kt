package com.seb.campustimetable.data.model

import android.graphics.Color

object SampleSchedule {
    // Monday 2026-08-31 makes the screenshot's 2026-10-26 date the start of week 9.
    val semesterStart = java.time.LocalDate.of(2026, 8, 31)

    val courses = listOf(
        CourseBlock("sketch", "结构素描", "未填写", "教学楼②701 · 画室", 1, 1, 4, Color.rgb(76, 165, 222)),
        CourseBlock("ethics", "思想道德与法治", "未填写", "教学楼②503", 3, 1, 2, Color.rgb(117, 185, 195)),
        CourseBlock("labor", "劳动教育 I", "杨钊、杨勋", "楼207", 4, 1, 2, Color.rgb(246, 158, 0)),
        CourseBlock("lab_safety", "实验室安全与防护", "杨钊、杨勋", "楼104", 5, 1, 2, Color.rgb(4, 183, 217)),
        CourseBlock("ai_literacy", "人工智能通识教育", "未填写", "教学楼②407", 3, 3, 4, Color.rgb(178, 148, 219)),
        CourseBlock("inorganic_am", "无机及分析化学", "杨钊、杨勋", "楼209", 4, 3, 4, Color.rgb(208, 157, 210)),
        CourseBlock("english", "大学英语 I", "杨钊、杨勋", "楼103", 5, 3, 4, Color.rgb(255, 207, 0)),
        CourseBlock("calculus_mon", "高等数学 III（1）", "未填写", "教学楼②511", 1, 6, 7, Color.rgb(63, 195, 126)),
        CourseBlock("psychology", "大学生心理健康教育", "未填写", "教学楼①308", 2, 6, 7, Color.rgb(61, 201, 184)),
        CourseBlock("military", "军事理论", "杨钊、杨勋", "楼206", 3, 6, 7, Color.rgb(255, 151, 0)),
        CourseBlock("inorganic_pm", "无机及分析化学", "杨钊、杨勋", "楼506", 5, 6, 7, Color.rgb(255, 151, 0)),
        CourseBlock("sports", "体育 I", "白云", "北田径场", 1, 8, 9, Color.rgb(247, 174, 190)),
        CourseBlock("calculus_tue", "高等数学 III（1）", "未填写", "教学楼②511", 2, 8, 9, Color.rgb(63, 195, 126)),
        CourseBlock("xi_ideology_wed", "习近平新时代中国特色社会主义思想概论", "杨钊、杨勋", "", 3, 8, 9, Color.rgb(104, 145, 219)),
        CourseBlock("xi_ideology_fri", "习近平新时代中国特色社会主义思想概论", "杨钊、杨勋", "", 5, 8, 9, Color.rgb(255, 151, 0)),
        CourseBlock("chemistry_lab", "通用化学实验 I", "未填写", "实验A中303 · 大学通用化学实验室1", 1, 10, 12, Color.rgb(255, 158, 0)),
        CourseBlock("packaging", "包装工程导论", "未填写", "教学楼②402", 3, 10, 12, Color.rgb(211, 164, 36)),
    )
}
