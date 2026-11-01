package cn.run.freepocket.model

enum class Category(val wire: String) {
    PC("pc"), ANDROID("android"), FAB("fab");

    companion object {
        fun fromWire(value: String): Category = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("未知商品分类：$value")
    }
}

enum class Ownership(val wire: String) {
    UNKNOWN("unknown"), OWNED("owned"), UNOWNED("unowned"), PARTIAL("partial"), EXPIRED("expired"), ERROR("error");

    companion object {
        fun fromWire(value: String): Ownership = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("未知拥有状态：$value")
    }
}

enum class Availability(val wire: String) {
    ACTIVE("active"), UPCOMING("upcoming"), EXPIRED("expired");

    companion object {
        fun fromWire(value: String): Availability = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("未知活动状态：$value")
    }
}

enum class FabTier(val wire: String) {
    PERSONAL("personal"), PROFESSIONAL("professional");

    companion object {
        fun fromWire(value: String): FabTier = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("未知 Fab 许可：$value")
    }
}
