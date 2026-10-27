package dev.gomoku.plugin;

import com.intellij.ide.plugins.RepositoryHelper;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;

/** 安装后首次打开项目时，自动配置更新源。 */
public final class UpdateRepositoryStartup implements StartupActivity.DumbAware {
    static final String REPOSITORY_URL = "https://zhugetian.com/gomoku/updatePlugins.xml";
    static final String CONFIGURED = "moyu.updateRepository.autoConfigured";

    @Override public void runActivity(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) configureRepository();
        });
    }

    void configureRepository() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(CONFIGURED)) return;
        if (!RepositoryHelper.getPluginHosts().contains(REPOSITORY_URL)) {
            UpdateSettings.getInstance().getStoredPluginHosts().add(REPOSITORY_URL);
        }
        // 完成后只记一次，避免多项目重复添加，也尊重用户日后手动移除的选择。
        properties.setValue(CONFIGURED, true);
    }
}
