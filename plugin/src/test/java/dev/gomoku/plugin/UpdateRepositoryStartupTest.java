package dev.gomoku.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.updateSettings.impl.UpdateOptions;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import java.util.List;

public final class UpdateRepositoryStartupTest extends BasePlatformTestCase {
    private UpdateOptions previousSettings;
    private String previousConfigured;
    private String previousPrompt;
    private final UpdateRepositoryStartup startup = new UpdateRepositoryStartup();
    private static final String OLD_PROMPT = "moyu.updateRepository.promptShown";
    private static final String OTHER_REPOSITORY = "https://example.org/plugins.xml";

    @Override protected void setUp() throws Exception {
        super.setUp();
        UpdateSettings settings = UpdateSettings.getInstance();
        previousSettings = settings.getState();
        settings.loadState(new UpdateOptions());
        settings.getStoredPluginHosts().add(OTHER_REPOSITORY);
        PropertiesComponent properties = PropertiesComponent.getInstance();
        previousConfigured = properties.getValue(UpdateRepositoryStartup.CONFIGURED);
        previousPrompt = properties.getValue(OLD_PROMPT);
        properties.unsetValue(UpdateRepositoryStartup.CONFIGURED);
        properties.unsetValue(OLD_PROMPT);
    }

    @Override protected void tearDown() throws Exception {
        try {
            UpdateSettings.getInstance().loadState(previousSettings);
            PropertiesComponent properties = PropertiesComponent.getInstance();
            properties.setValue(UpdateRepositoryStartup.CONFIGURED, previousConfigured);
            properties.setValue(OLD_PROMPT, previousPrompt);
        } finally { super.tearDown(); }
    }

    public void testFirstStartAddsRepositoryAndPersistsWithoutReplacingOtherHosts() {
        UpdateSettings settings = UpdateSettings.getInstance();
        startup.configureRepository();
        assertEquals(List.of(OTHER_REPOSITORY, UpdateRepositoryStartup.REPOSITORY_URL), settings.getStoredPluginHosts());
        assertTrue(PropertiesComponent.getInstance().getBoolean(UpdateRepositoryStartup.CONFIGURED));
        UpdateOptions restored = XmlSerializer.deserialize(XmlSerializer.serialize(settings.getState()), UpdateOptions.class);
        settings.loadState(restored);
        new UpdateRepositoryStartup().configureRepository();
        assertEquals(List.of(OTHER_REPOSITORY, UpdateRepositoryStartup.REPOSITORY_URL), settings.getStoredPluginHosts());
    }

    public void testExistingRepositoryIsNotDuplicated() {
        List<String> hosts = UpdateSettings.getInstance().getStoredPluginHosts();
        hosts.add(UpdateRepositoryStartup.REPOSITORY_URL);
        startup.configureRepository();
        assertEquals(List.of(OTHER_REPOSITORY, UpdateRepositoryStartup.REPOSITORY_URL), hosts);
    }

    public void testLaterRemovalIsRespected() {
        startup.configureRepository();
        List<String> hosts = UpdateSettings.getInstance().getStoredPluginHosts();
        hosts.remove(UpdateRepositoryStartup.REPOSITORY_URL);
        new UpdateRepositoryStartup().configureRepository();
        assertEquals(List.of(OTHER_REPOSITORY), hosts);
    }

    public void testUpgradeFromOldPromptVersionStillConfiguresRepository() {
        PropertiesComponent.getInstance().setValue(OLD_PROMPT, true);
        startup.configureRepository();
        assertEquals(List.of(OTHER_REPOSITORY, UpdateRepositoryStartup.REPOSITORY_URL),
                UpdateSettings.getInstance().getStoredPluginHosts());
    }

    public void testDisabledUpdateChecksArePreserved() {
        UpdateSettings settings = UpdateSettings.getInstance();
        settings.setPluginsCheckNeeded(false);
        settings.setCheckNeeded(false);
        startup.configureRepository();
        assertFalse(settings.isPluginsCheckNeeded());
        assertFalse(settings.isCheckNeeded());
        assertTrue(settings.getStoredPluginHosts().contains(UpdateRepositoryStartup.REPOSITORY_URL));
    }
}
