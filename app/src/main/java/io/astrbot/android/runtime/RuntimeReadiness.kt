package io.astrbot.android.runtime

import java.io.File

/** Current runtime layout only. QQ is a separately managed package, never a base-image prerequisite. */
internal object RuntimeReadiness {
    val requiredFiles = listOf(".astrbot-bootstrap.json", "opt/astrbot/main.py", "opt/astrbot-venv/pyvenv.cfg",
        "var/lib/bigfish/runtime-components.json")
    fun complete(root: File): Boolean = requiredFiles.all { File(root,it).isFile }
}
