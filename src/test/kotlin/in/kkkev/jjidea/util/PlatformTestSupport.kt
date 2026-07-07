package `in`.kkkev.jjidea.util

import com.intellij.util.ui.UIUtil

/**
 * Best-effort drain of the fire-and-forget pooled-thread loaders that [JujutsuStateModel]'s
 * `init` launches (`Messaging.invalidate` -> `runInBackground { ... runLater { ... } }`, see
 * `in.kkkev.jjidea.jj.JujutsuStateModel` and `in.kkkev.jjidea.util.Tasks.runInBackground`).
 * Those closures capture the fixture `project`; if one is still in flight when `projectFixture`
 * disposes the project, IntelliJ's `LeakHunter` reports a retained `Project` (a flake that
 * passes locally and fails only under CI timing — see jj-idea-q49j).
 *
 * Call from `@AfterEach` in platform tests that touch `project.stateModel`, before the
 * `projectFixture` extension tears the project down. Repeatedly pumps the EDT with short
 * sleeps in between to give the pooled threads a chance to finish and post their `runLater`.
 * There's no observable "done" signal for the loaders, so this is a fixed, bounded pump rather
 * than true quiescence detection — good enough to make the race far less likely, with the CI
 * retry (jj-idea-q49j) as the reliable safety net.
 */
fun drainBackgroundLoads(
    timeoutMillis: Long = 1_000
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        UIUtil.dispatchAllInvocationEvents()
        Thread.sleep(20) // let pooled-thread loaders finish and post their runLater
        UIUtil.dispatchAllInvocationEvents()
    }
}
