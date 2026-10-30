package io.github.caozl9021.mymoney.data

import com.example.billkit.DebugBus
import java.io.File

/** Saves the original fixture text plus a colocated, human-readable tab-separated index. */
object SampleIndex {
    @Synchronized fun save(directory: File, state: DebugBus.State): File {
        directory.mkdirs()
        val base = SampleExport.sampleFileName(state.packageName, state.atMillis)
        var target = File(directory, base); var n = 1
        while (target.exists()) target = File(directory, base.removeSuffix(".txt") + "-${n++}.txt")
        atomicText(target, buildString {
            append("package: ${state.packageName}\nactivity: ${state.activityClass}\n")
            append("source: ${state.source}\noutcome: ${state.outcome}\nat: ${state.atMillis}\n\n")
            append(state.text)
        })
        val index = File(directory, "index.txt")
        fun safe(value: String?) = value.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        val entry = listOf(target.name, state.atMillis.toString(), safe(state.packageName), safe(state.source),
            safe(state.outcome), safe(state.activityClass)).joinToString("\t") + "\n"
        val previous = if (index.exists()) index.readText() else "file\tatMillis\tpackage\tsource\toutcome\tactivity\n"
        atomicText(index, previous + entry)
        return target
    }
}
