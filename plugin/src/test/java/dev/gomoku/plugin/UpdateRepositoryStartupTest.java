package dev.gomoku.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.updateSettings.impl.UpdateOptions;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import java.util.ArrayList;
import java.util.List;

public final class UpdateRepositoryStartupTest extends BasePlatformTestCase {
    private UpdateOptions previousSettings;
    private String previousPrompt;
    private final List<Notification> notices = new ArrayList<>();
    private final UpdateRepositoryStartup startup = new UpdateRepositoryStartup();
    private static final String OTHER_REPOSITORY = "https://example.org/plugins.xml";

    @Override protected void setUp() throws Exception {
        super.setUp();
        UpdateSettings settings = UpdateSettings.getInstance();
        previousSettings = settings.getState();
        settings.loadState(new UpdateOptions());
        settings.getStoredPluginHosts().add(OTHER_REPOSITORY);
        previousPrompt = PropertiesComponent.getInstance().getValue(UpdateRepositoryStartup.PROMPT_SHOWN);
        PropertiesComponent.getInstance().unsetValue(UpdateRepositoryStartup.PROMPT_SHOWN);
        ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
                .subscribe(Notifications.TOPIC, new Notifications() {
                    @Override public void notify(Notification notification) {
                        if (notification.getGroupId().equals("Moyu Updates")) notices.add(notification);
                    }
                });
    }

    @Override protected void tearDown() throws Exception {
        try {
            for (Notification notice : notices) notice.expire();
            UpdateSettings.getInstance().loadState(previousSettings);
            PropertiesComponent.getInstance().setValue(UpdateRepositoryStartup.PROMPT_SHOWN, previousPrompt);
        } finally { super.tearDown(); }
    }

    public void testConsentAddsRepositoryAndPersistsWithoutReplacingOtherHosts() {
        UpdateSettings settings = UpdateSettings.getInstance();
        Notification prompt = startup.showPrompt();
        assertNotNull(prompt);
        assertEquals(List.of(OTHER_REPOSITORY), settings.getStoredPluginHosts());
        assertNull(startup.showPrompt());
        Notification.fire(prompt, prompt.getActions().get(0), id -> Notification.KEY.is(id) ? prompt : null);
        assertTrue(prompt.isExpired());
        assertEquals(List.of(OTHER_REPOSITORY, UpdateRepositoryStartup.REPOSITORY_URL), settings.getStoredPluginHosts());
        UpdateOptions restored = XmlSerializer.deserialize(XmlSerializer.serialize(settings.getState()), UpdateOptions.class);
        settings.loadState(restored);
        assertTrue(settings.getStoredPluginHosts().contains(UpdateRepositoryStartup.REPOSITORY_URL));
        assertNull(new UpdateRepositoryStartup().showPrompt());
    }

    public void testDeclineLeavesSettingsUnchangedAndDoesNotPromptAgain() {
        Notification prompt = startup.showPrompt();
        Notification.fire(prompt, prompt.getActions().get(1), id -> Notification.KEY.is(id) ? prompt : null);
        assertTrue(prompt.isExpired());
        assertEquals(List.of(OTHER_REPOSITORY), UpdateSettings.getInstance().getStoredPluginHosts());
        assertNull(new UpdateRepositoryStartup().showPrompt());
    }

    public void testExistingRepositoryAndLaterRemovalAreRespected() {
        List<String> hosts = UpdateSettings.getInstance().getStoredPluginHosts();
        hosts.add(UpdateRepositoryStartup.REPOSITORY_URL);
        assertNull(startup.showPrompt());
        assertEquals(2, hosts.size());
        hosts.remove(UpdateRepositoryStartup.REPOSITORY_URL);
        assertNull(new UpdateRepositoryStartup().showPrompt());
        assertEquals(List.of(OTHER_REPOSITORY), hosts);
    }

    public void testRepositoryAddedWhilePromptOpenIsNotDuplicated() {
        Notification prompt = startup.showPrompt();
        List<String> hosts = UpdateSettings.getInstance().getStoredPluginHosts();
        hosts.add(UpdateRepositoryStartup.REPOSITORY_URL);
        Notification.fire(prompt, prompt.getActions().get(0), id -> Notification.KEY.is(id) ? prompt : null);
        assertEquals(List.of(OTHER_REPOSITORY, UpdateRepositoryStartup.REPOSITORY_URL), hosts);
    }

    public void testClosedPromptDoesNotReappearAndDoesNotConfigureRepository() {
        startup.showPrompt().expire();
        assertNull(new UpdateRepositoryStartup().showPrompt());
        assertEquals(List.of(OTHER_REPOSITORY), UpdateSettings.getInstance().getStoredPluginHosts());
    }

    public void testDisabledUpdateChecksArePreservedAndExplained() {
        UpdateSettings settings = UpdateSettings.getInstance();
        settings.setPluginsCheckNeeded(false);
        settings.setCheckNeeded(false);
        Notification prompt = startup.showPrompt();
        Notification.fire(prompt, prompt.getActions().get(0), id -> Notification.KEY.is(id) ? prompt : null);
        assertFalse(settings.isPluginsCheckNeeded());
        assertFalse(settings.isCheckNeeded());
        assertTrue(notices.stream().anyMatch(n -> n.getContent().contains("已关闭插件更新检查")));
    }
}
