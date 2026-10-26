/*
 * Adapted from datouggg/missNovel (commit f94dcef71b9f98e05993507e3453fba562aab642).
 * Licensed under Apache License 2.0; see META-INF/licenses/missNovel-Apache-2.0.txt.
 * Modified for Inlay Games: isolated namespace/storage, Java 17 baseline and shared controls.
 */
package dev.gomoku.novel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import dev.gomoku.plugin.GomokuSession;

public final class ReaderStartup implements StartupActivity.DumbAware {
    @Override public void runActivity(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                project.getService(GomokuSession.class);
                NovelReader.getInstance().start(project);
            }
        });
    }
}
