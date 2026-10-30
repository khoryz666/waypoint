package com.khoryz666.waypoint.domain

/**
 * The UI language picked at onboarding. Deterministic text built in domain/ (receipts, daily
 * close) can't read Android resources, so it chooses between the two strings itself.
 */
enum class Lang(val tag: String) {
    EN("en"), ZH("zh-CN");

    fun t(en: String, zh: String): String = if (this == ZH) zh else en

    companion object {
        fun of(tag: String?): Lang = if (tag?.startsWith("zh") == true) ZH else EN
    }
}
