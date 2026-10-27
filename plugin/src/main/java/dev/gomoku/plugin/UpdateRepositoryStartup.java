package dev.gomoku.plugin;

import com.intellij.ide.plugins.RepositoryHelper;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;

/** 首次打开项目时，经用户点击同意后配置更新源。 */
public final class UpdateRepositoryStartup implements StartupActivity.DumbAware {
    static final String REPOSITORY_URL = "https://zhugetian.com/gomoku/updatePlugins.xml";
    static final String PROMPT_SHOWN = "moyu.updateRepository.promptShown";
    private static final String GROUP = "Moyu Updates";

    @Override public void runActivity(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) showPrompt();
        });
    }

    Notification showPrompt() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(PROMPT_SHOWN)) return null;
        // 应用级标记避免多个项目重复提示，也尊重用户之后手动移除更新源的选择。
        properties.setValue(PROMPT_SHOWN, true);
        if (RepositoryHelper.getPluginHosts().contains(REPOSITORY_URL)) return null;

        Notification prompt = notification("启用摸鱼合集更新提醒？",
                "启用后，IDE 将从 zhugetian.com 检查并下载摸鱼合集更新。"
                        + "发现兼容的新版本后，可在插件设置中点击更新。此提示只显示一次。");
        prompt.addAction(NotificationAction.createSimpleExpiring("启用更新提醒", () -> {
            UpdateSettings settings = UpdateSettings.getInstance();
            if (!RepositoryHelper.getPluginHosts().contains(REPOSITORY_URL)) {
                settings.getStoredPluginHosts().add(REPOSITORY_URL);
            }
            String detail = settings.isPluginsCheckNeeded()
                    ? "可在 IDE 的更新设置中检查更新，后续提醒由 IDE 的检查设置决定。"
                    : "当前 IDE 已关闭插件更新检查，请在设置 → 更新中开启插件更新检查。";
            notification("摸鱼合集更新源已添加", detail).notify(null);
        }));
        prompt.addAction(NotificationAction.createSimpleExpiring("暂不启用", () -> {}));
        prompt.notify(null);
        return prompt;
    }

    private static Notification notification(String title, String content) {
        return NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(title, content, NotificationType.INFORMATION);
    }
}
