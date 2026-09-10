/* Copyright 2026 AgentMirror Project Authors. Licensed under Apache-2.0. */
package dev.agentmirror.app.termview

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class TermDrawControlSessionTest {
    private class Queue {
        val tasks = java.util.ArrayDeque<Runnable>()
        fun post(task: Runnable) { tasks.addLast(task) }
        fun next() { check(tasks.isNotEmpty()); tasks.removeFirst().run() }
    }

    @Test
    fun eventsCoalesceAndIdleHasNoScheduledReads() {
        val io = Queue(); val main = Queue()
        var reads = 0
        val seen = mutableListOf<TermDrawControlUpdate>()
        val session = TermDrawControlSession({ reads++; TermDrawControlUpdate(true) }, io::post, main::post, seen::add)
        check(io.tasks.isEmpty() && main.tasks.isEmpty())
        repeat(1000) { session.request() }
        check(io.tasks.size == 1)
        io.next(); main.next()
        check(reads == 1 && seen.size == 1)
        check(io.tasks.isEmpty() && main.tasks.isEmpty())
        session.close()
    }

    @Test
    fun eventsDuringDeliveryScheduleOneFollowupWithoutUnboundedQueue() {
        val io = Queue(); val main = Queue()
        var reads = 0
        val session = TermDrawControlSession({ reads++; TermDrawControlUpdate() }, io::post, main::post, {})
        session.request(); io.next()
        repeat(1000) { session.request() }
        check(io.tasks.isEmpty() && main.tasks.size == 1)
        main.next()
        check(io.tasks.size == 1)
        io.next(); main.next()
        check(reads == 2 && io.tasks.isEmpty() && main.tasks.isEmpty())
    }

    @Test
    fun closeBeforeReadDoesNoIo() {
        val io = Queue(); val main = Queue()
        var reads = 0
        val session = TermDrawControlSession({ reads++; TermDrawControlUpdate() }, io::post, main::post, { error("late") })
        session.request(); session.close(); io.next(); session.request()
        check(reads == 0 && io.tasks.isEmpty() && main.tasks.isEmpty())
    }

    @Test
    fun lateOldDeliveryCannotAffectNewSession() {
        val io = Queue(); val main = Queue()
        val seen = mutableListOf<Int>()
        val old = TermDrawControlSession({ TermDrawControlUpdate(burst = 1) }, io::post, main::post, { seen.add(it.burst) })
        old.request(); io.next(); old.close()
        val new = TermDrawControlSession({ TermDrawControlUpdate(burst = 2) }, io::post, main::post, { seen.add(it.burst) })
        new.request(); io.next(); main.next(); main.next()
        check(seen == listOf(2))
    }

    @Test
    fun retireAfterClaimedReadDeliversBurstBeforeClosing() = inDirectory { dir ->
        File(dir, TermDrawControlFiles.BURST).writeText("120")
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val dispatched = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val main = ConcurrentLinkedQueue<Runnable>()
        val seen = mutableListOf<Int>()
        val reader = TermDrawControlFiles(dir)
        val session = TermDrawControlSession(
            read = {
                val update = reader.read() // Real move-to-reading + delete path.
                readStarted.countDown()
                check(releaseRead.await(2, TimeUnit.SECONDS))
                update
            },
            execute = { task -> Thread(task, "term-draw-test").start() },
            dispatch = { task -> main.add(task); dispatched.countDown() },
            consume = { seen += it.burst },
        )
        session.request()
        check(readStarted.await(2, TimeUnit.SECONDS))
        session.closeWhenIdle { retired.countDown() }
        check(!retired.await(50, TimeUnit.MILLISECONDS)) // close must not block for I/O.
        releaseRead.countDown()
        check(dispatched.await(2, TimeUnit.SECONDS))
        checkNotNull(main.poll()).run()
        check(retired.await(2, TimeUnit.SECONDS))
        check(seen == listOf(120))
        session.close()
    }

    @Test
    fun controlFilesAreBoundedAndBurstConsumedExactlyOnce() = inDirectory { dir ->
        val reader = TermDrawControlFiles(dir)
        check(reader.read() == TermDrawControlUpdate())
        File(dir, TermDrawControlFiles.OPT).writeText("0\n")
        File(dir, TermDrawControlFiles.BURST).writeText("120\n")
        check(reader.read() == TermDrawControlUpdate(false, 120))
        check(reader.read() == TermDrawControlUpdate(false, 0))
        check(!File(dir, TermDrawControlFiles.BURST).exists())
        check(!File(dir, ".${TermDrawControlFiles.BURST}.reading").exists())
        File(dir, TermDrawControlFiles.OPT).writeText("1".repeat(65))
        File(dir, TermDrawControlFiles.BURST).writeText("9".repeat(65))
        check(reader.read() == TermDrawControlUpdate())
    }

    @Test
    fun malformedCommandsKeepOptStateAndDoNotArmBurst() = inDirectory { dir ->
        val reader = TermDrawControlFiles(dir)
        File(dir, TermDrawControlFiles.OPT).mkdir() // unreadable as a regular file
        for (value in listOf("", "bad", "-1", "2147483648")) {
            File(dir, TermDrawControlFiles.BURST).writeText(value)
            check(reader.read() == TermDrawControlUpdate())
        }
        check(!TermDrawControlFiles.accepts(null))
        check(!TermDrawControlFiles.accepts("diag.log"))
        check(!TermDrawControlFiles.accepts(".term_draw_burst.reading"))
        check(TermDrawControlFiles.accepts(TermDrawControlFiles.OPT))
        check(TermDrawControlFiles.accepts(TermDrawControlFiles.BURST))
    }

    @Test
    fun fileCreatedAfterInitialReadAndReplacementCommandsAreHandled() = inDirectory { dir ->
        val reader = TermDrawControlFiles(dir)
        check(reader.read().burst == 0)
        File(dir, TermDrawControlFiles.BURST).writeText("7")
        check(reader.read().burst == 7)
        File(dir, TermDrawControlFiles.BURST).writeText("9")
        check(reader.read().burst == 9)
        check(reader.read().burst == 0)
    }

    private fun inDirectory(body: (File) -> Unit) {
        val dir = Files.createTempDirectory("term-draw-controls-test").toFile()
        try { body(dir) } finally { dir.deleteRecursively() }
    }
}
