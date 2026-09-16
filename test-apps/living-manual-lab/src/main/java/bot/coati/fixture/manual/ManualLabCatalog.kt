package bot.coati.fixture.manual

internal data class LabEntry(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val details: List<String>,
)

internal object ManualLabCatalog {
    val entries = listOf(
        LabEntry(
            id = "travel",
            title = "离线旅行清单",
            category = "出行",
            summary = "整理出发前需要确认的常用事项。",
            details = listOf("确认身份证件", "下载离线地图", "准备充电设备"),
        ),
        LabEntry(
            id = "meeting",
            title = "会议准备清单",
            category = "工作",
            summary = "帮助会前核对材料、设备和参会信息。",
            details = listOf("检查会议议程", "准备演示文件", "确认会议地点"),
        ),
        LabEntry(
            id = "emergency",
            title = "家庭应急清单",
            category = "生活",
            summary = "记录家庭应急用品的基础准备项目。",
            details = listOf("检查饮用水", "检查照明工具", "记录紧急联系人"),
        ),
    )

    fun find(id: String): LabEntry = entries.firstOrNull { it.id == id } ?: entries.first()

    fun search(query: String): List<LabEntry> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return entries
        return entries.filter { entry ->
            entry.title.contains(normalized, ignoreCase = true) ||
                entry.category.contains(normalized, ignoreCase = true) ||
                entry.summary.contains(normalized, ignoreCase = true)
        }
    }
}
