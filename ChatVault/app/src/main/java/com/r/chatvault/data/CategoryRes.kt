package com.r.chatvault.data

import com.r.chatvault.R

/**
 * 数据库里的主题名是中文常量，展示时映射到字符串资源。
 * 这里是唯一映射表：UI 和「数据层拼文案」的地方都走它，避免两处不一致。
 */
object CategoryRes {

    fun idOf(raw: String): Int? = when (raw) {
        "安卓玩机" -> R.string.cat_android
        "网络路由" -> R.string.cat_network
        "编程开发" -> R.string.cat_dev
        "电脑系统" -> R.string.cat_pc
        "影音图像" -> R.string.cat_media
        "硬件性能" -> R.string.cat_hardware
        "学习办公" -> R.string.cat_office
        "生活杂问" -> R.string.cat_life
        "其他" -> R.string.cat_other
        else -> null
    }
}
