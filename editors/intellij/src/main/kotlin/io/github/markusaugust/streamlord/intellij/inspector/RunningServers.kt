package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import io.github.markusaugust.streamlord.analysis.RunningServer
import io.github.markusaugust.streamlord.analysis.ServerLog
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The servers this project has started from a run configuration, read from their own output.
 *
 * The port a server listens on is set in a profile, an environment variable or the run
 * configuration, and the inspector cannot read all of those. The server says it once when it
 * starts, "Tomcat started on port 9102 (http) with context path '/'", and that line is the answer
 * wherever the port came from. The last lines are kept too, so a failed request can show the
 * warning the server logged about it.
 */
@Service(Service.Level.PROJECT)
class RunningServers {
    internal class Line(
        val at: Long,
        val text: String,
    )

    internal class Run(
        val name: String,
    ) {
        @Volatile var url: String? = null

        @Volatile var startedAt: Long = 0
        val lines = ConcurrentLinkedDeque<Line>()
        val partial = StringBuilder()
    }

    private val runs = CopyOnWriteArrayList<Run>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Follow [handler]'s output as the process called [name]. */
    fun track(
        name: String,
        handler: ProcessHandler,
    ) {
        val run = Run(name)
        runs += run
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(
                    event: ProcessEvent,
                    outputType: Key<*>,
                ) {
                    // The IDE's own lines, such as the command line it ran, are not the server's.
                    if (outputType != ProcessOutputTypes.SYSTEM) read(run, event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    if (runs.remove(run) && run.url != null) changed()
                }
            },
        )
    }

    /** Take [text] as it arrives, which is not always a whole line, and keep each line once it is. */
    internal fun read(
        run: Run,
        text: String,
    ) {
        val complete = ArrayList<String>()
        synchronized(run) {
            run.partial.append(text)
            while (true) {
                val end = run.partial.indexOf("\n")
                if (end < 0) break
                complete += run.partial.substring(0, end).trimEnd('\r')
                run.partial.delete(0, end + 1)
            }
        }
        for (line in complete) {
            val now = System.currentTimeMillis()
            val plain = ServerLog.plain(line)
            if (run.url == null) {
                ServerLog.startedAt(plain)?.let {
                    run.url = it
                    run.startedAt = now
                    changed()
                }
            }
            run.lines.addLast(Line(now, plain))
            while (run.lines.size > KEEP) run.lines.pollFirst()
        }
    }

    /** The server that started last and still runs, or null. */
    fun current(): RunningServer? =
        runs
            .filter { it.url != null }
            .maxByOrNull { it.startedAt }
            ?.let { RunningServer(it.url!!, it.name) }

    /** The warnings and errors the servers logged at or after [since], oldest first, at most [SHOWN]. */
    fun problemsSince(since: Long): List<String> =
        runs
            .flatMap { run -> run.lines.filter { it.at >= since && ServerLog.isProblem(it.text) } }
            .sortedBy { it.at }
            .take(SHOWN)
            .map { it.text }

    /** Call [listener] when a server starts or stops, until [parent] is disposed, so what shows `{{baseUrl}}` can follow. */
    fun onChange(
        parent: Disposable,
        listener: () -> Unit,
    ) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun changed() = listeners.forEach { it() }

    companion object {
        private const val KEEP = 500
        private const val SHOWN = 10

        fun getInstance(project: Project): RunningServers = project.service()
    }
}

/** Hands every process the project starts to [RunningServers]. Registered in plugin.xml. */
class RunningServersListener(
    private val project: Project,
) : ExecutionListener {
    override fun processStarted(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
    ) {
        RunningServers.getInstance(project).track(env.runProfile.name, handler)
    }
}
