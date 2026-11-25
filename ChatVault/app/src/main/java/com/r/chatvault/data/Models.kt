package com.r.chatvault.data

import org.json.JSONArray

/** 一个会话（索引层，不含消息正文） */
data class Conversation(
    val id: String,
    val title: String,
    val createTs: Long,
    val updateTs: Long,
    val msgCount: Int,
    val charCount: Int,
    val model: String?,
    val category: String,
    val starred: Boolean,
    val attachmentCount: Int,
    val alias: String? = null,
    val tagsCsv: String = ""
) {
    /** 用户自定义名称优先，未改名时用导出包里的标题 */
    val displayTitle: String
        get() = alias?.takeIf { it.isNotBlank() } ?: title

    val tags: List<String>
        get() = tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** 一条消息。active=false 表示被编辑废弃的分支节点，只在分支浏览里出现 */
data class Message(
    val id: String,
    val convId: String,
    val role: String,
    val text: String,
    val reasoning: String?,
    val ts: Long,
    val order: Int,
    val contentType: String,
    val model: String?,
    val parentId: String? = null,
    val childIds: String = "",
    val active: Boolean = true
) {
    val childIdList: List<String>
        get() = runCatching {
            val arr = JSONArray(childIds)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
}

/** 附件（.dat 实体 或 只有记录） */
data class Attachment(
    val id: String,
    val convId: String?,
    val msgId: String?,
    val name: String,
    val mime: String,
    val size: Long,
    val path: String?,
    val kind: String,
    val exists: Boolean
)

/** 搜索命中。type: message / code / clip / file */
data class SearchHit(
    val convId: String,
    val convTitle: String,
    val msgId: String,
    val role: String,
    val ts: Long,
    val snippet: String,
    val order: Int,
    val type: String = "message",
    val lang: String? = null,
    val attachment: Attachment? = null,
    val clipId: Long = 0L
)

/** 摘录 / 书签 */
data class ClipItem(
    val id: Long,
    val convId: String?,
    val msgId: String?,
    val text: String,
    val note: String?,
    val tags: String,
    val kind: String,
    val createdTs: Long,
    val convTitle: String?,
    val canRevert: Boolean = false
) {
    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** 代码片段 */
data class CodeItem(
    val id: Long,
    val convId: String,
    val msgId: String,
    val lang: String,
    val code: String,
    val ts: Long,
    val convTitle: String?
)

data class TagCount(val name: String, val count: Int)

data class MonthlyStat(val key: String, val convs: Int, val msgs: Int, val chars: Int)
data class NamedCount(val name: String, val count: Int)

data class StatsBundle(
    val monthly: List<MonthlyStat> = emptyList(),
    val categories: List<NamedCount> = emptyList(),
    val hours: List<NamedCount> = emptyList(),
    val models: List<NamedCount> = emptyList(),
    val years: List<Int> = emptyList(),
    val totalConvs: Int = 0,
    val totalMsgs: Int = 0,
    val totalChars: Int = 0,
    val totalAttachments: Int = 0,
    val missingAttachments: Int = 0
)

data class ImportSummary(
    val added: Int,
    val updated: Int,
    val skipped: Int,
    val messages: Int,
    val attachments: Int,
    val missing: Int,
    val orphanFiles: Int,
    val files: Int,
    val mdFiles: Int = 0
)

/** 导入结束后的可点报告：缺失附件与未引用文件都能直接打开 */
data class ImportReport(
    val summary: ImportSummary,
    val missing: List<Attachment> = emptyList(),
    val orphans: List<Attachment> = emptyList()
)

/** 主题归类：命中词越多越可信，全部不命中则归到「其他」 */
object Classifier {

    private val rules: List<Pair<String, List<String>>> = listOf(
        "安卓玩机" to listOf(
            "magisk", "apatch", "kernelsu", "sukisu", "root", "刷机", "boot", "recovery", "面具", "模块",
            "twrp", "9008", "fastboot", "adb", "分区", "安卓", "内核", "xposed", "lsposed", "rom",
            "刷入", "槽位", "shamiko", "zram", "交换分区", "开机动画", "开机引导", "指纹", "图标", "壁纸",
            "hosts", "私有目录", "apk", "包名", "线刷", "重启", "一加", "红米", "vivo", "iqoo", "骁龙",
            "鸿蒙", "澎湃", "主题", "手机", "锁屏", "截屏", "录屏", "投屏", "蓝牙", "充电", "电池",
            "温控", "触控", "屏幕", "刷新率", "存储", "清理", "文件管理", "汉化", "虚拟定位", "模拟定位",
            "快捷方式", "mt管理器", "相册", "微信", "快手"
        ),
        "网络路由" to listOf(
            "路由器", "openwrt", "wifi", "wi-fi", "wlan", "宽带", "网速", "dns", "抓包", "vpn", "代理",
            "机场", "校园网", "giwifi", "网络", "网关", "桥接", "信道", "dhcp", "端口", "域名", "cdn",
            "软路由", "旁路由", "ddns", "ipv6", "内网", "frp", "nat", "热点", "流量", "延迟", "ping",
            "mac地址", "网线", "t568b", "中继", "5g", "4g", "频段", "2.4g", "防火墙", "穿透", "ssh",
            "scp", "curl", "ntp", "服务器", "vps", "阿里云", "局域网", "串流", "联机", "加速器",
            "telegram", "电报", "tiktok", "油管", "youtube", "谷歌", "浏览器", "下载速度", "带宽",
            "静态ip", "子网", "证书", "kml", "坐标", "经纬度", "定位", "投屏", "桥接"
        ),
        "编程开发" to listOf(
            "python", "java", "c语言", "c++", "脚本", "代码", "html", "css", "js", "javascript", "前端",
            "后端", "sql", "api", "termux", "shell", "bat", "cmd", "命令行", "正则", "json", "数据库",
            "access", "爬虫", "函数", "程序", "编译", "github", "git", "环境变量", "打包", "xml",
            "接口", "linux", "ubuntu", "vim", "markdown", "mysql", "aes", "加密", "哈希", "进制",
            "manifest", "仓库", "开源", "部署", "docker", "nginx", "php", "node", "网站", "网页",
            "算法", "变量", "循环", "数组", "指针", "报错", "调试", "编辑器", "vscode", "提示词", "公式"
        ),
        "电脑系统" to listOf(
            "windows", "win10", "win11", "win7", "office", "word", "ppt", "wps", "软件", "安装", "激活",
            "系统", "蓝屏", "报错", "清理", "c盘", "重装", "杀毒", "病毒", "压缩", "快捷键", "输入法",
            "驱动", "bios", "uefi", "mbr", "gpt", "u盘", "硬盘", "格式化", "文件夹", "引导", "启动",
            "注册表", "远程桌面", "虚拟机", "vmware", "双系统", "iso", "镜像"
        ),
        "影音图像" to listOf(
            "视频", "图片", "抠图", "剪辑", "编码", "ffmpeg", "分辨率", "相机", "照片", "音频", "音乐",
            "播放", "格式", "色彩", "调色", "转码", "字幕", "截图", "画质", "png", "jpg", "jpeg",
            "psd", "raw", "srgb", "p3", "色域", "位深", "噪点", "水印", "码率", "剪映", "ps", "pr",
            "ae", "br", "adobe", "audition", "esrgan", "dpi", "像素", "透明", "背景", "素材", "动画",
            "flash", "swf", "渲染", "导出"
        ),
        "硬件性能" to listOf(
            "cpu", "显卡", "内存", "ssd", "笔记本", "主板", "电源", "散热", "风扇", "显示器", "键盘",
            "鼠标", "usb", "i3", "i5", "i7", "amd", "intel", "nvidia", "跑分", "双通道", "ddr",
            "pcie", "扩展坞", "硬盘盒", "耳机", "转接", "功耗", "温度", "性能", "对比", "推荐",
            "选购", "代工", "型号", "拆机", "充电器", "充电宝", "功率", "电阻"
        ),
        "学习办公" to listOf(
            "试卷", "翻译", "论文", "作业", "题目", "答案", "英语", "数学", "物理", "化学", "历史",
            "地理", "考试", "实验", "课程", "自我介绍", "周记", "简历", "报告", "总结", "整理", "写作",
            "语法", "单词", "方程", "几何", "概率", "选择题", "生物", "知识点", "学习路线", "入门", "教程"
        ),
        "生活杂问" to listOf(
            "汇率", "投资", "股票", "a股", "港股", "上市", "房价", "保险", "医疗", "近视", "验光",
            "眼睛", "健康", "运动", "跑步", "饮食", "豆腐", "菜刀", "蝴蝶刀", "jk", "尺码", "服装",
            "天气", "气温", "南北极", "生态", "滇池", "法律", "管制", "举报", "投诉", "合同", "发票",
            "价格", "费用", "会员", "账号", "密码", "计划", "习惯", "分餐"
        )
    )

    fun classify(title: String): String {
        if (title.isBlank()) return "其他"
        val lower = title.lowercase()
        var best = "其他"
        var bestScore = 0
        for ((cat, words) in rules) {
            var score = 0
            for (w in words) if (lower.contains(w)) score++
            if (score > bestScore) {
                bestScore = score
                best = cat
            }
        }
        return best
    }
}
