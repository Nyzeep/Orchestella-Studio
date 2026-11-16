package io.github.nyzeep.orchestella.ui.shell

/**
 * The five Oe3 destinations in bar order (spec §3.1).
 *
 * [key] is the only value saved across recreation: enum ordinals are not a
 * stable contract once a tab is inserted, the string is.
 */
enum class Oe3Tab(val key: String, val label: String) {
    Workstation("workstation", "工作站"),
    Instances("instances", "实例"),
    Hardware("hardware", "硬件"),
    Distros("distros", "发行版"),
    Config("config", "配置"),
    ;

    companion object {
        fun fromKey(key: String?): Oe3Tab =
            entries.firstOrNull { it.key == key } ?: Workstation
    }
}
