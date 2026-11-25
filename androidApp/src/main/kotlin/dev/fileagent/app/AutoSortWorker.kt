package dev.fileagent.app

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.fileagent.agent.PlanExecutor
import dev.fileagent.rules.RuleSorter
import java.util.concurrent.TimeUnit

/**
 * Daily background tidy of Download/ using rules only (no LLM, no cost).
 * Every run is added to the undo history.
 */
class AutoSortWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!Environment.isExternalStorageManager()) return Result.failure()
        val graph = AppGraph.get(applicationContext)
        val now = System.currentTimeMillis()
        val plan = RuleSorter(graph.fs).plan("Download", now)
        if (plan.actions.none { it is dev.fileagent.agent.PlannedAction.Move }) return Result.success()
        val result = PlanExecutor(graph.fs).apply(plan, now)
        graph.saveUndo(graph.loadUndo() + result.batch)
        return Result.success()
    }

    companion object {
        private const val NAME = "auto_sort_downloads"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<AutoSortWorker>(1, TimeUnit.DAYS).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
