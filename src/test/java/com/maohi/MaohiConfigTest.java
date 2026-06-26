package com.maohi;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

public class MaohiConfigTest {

    @Test
    public void testDefaultConfigValues() {
        MaohiConfig config = new MaohiConfig();
        
        // 验证关键默认值
        assertEquals(4, config.minVirtualPlayers);
        assertEquals(4, config.maxVirtualPlayers);
        assertTrue(config.chatMessages.length > 0);
        assertNotNull(config.handItemsL1);
        assertNotNull(config.handItemsL2);
        assertNotNull(config.handItemsL3);
    }

    @Test
    public void testConfigReloadLogic() {
        // 修正：MaohiConfig 使用的是 getInstance() 而非 get()
        MaohiConfig instance1 = MaohiConfig.getInstance();
        MaohiConfig instance2 = MaohiConfig.getInstance();
        
        assertSame(instance1, instance2, "Should be the same singleton instance");
    }

    @Test
    public void testMsConversion() {
        MaohiConfig config = new MaohiConfig();
        config.offlineMinMinutes = 10;
        assertEquals(600000L, config.getOfflineMinMs(), "10 minutes should be 600,000ms");
        
config.sessionMinMinutes = 60;
	assertEquals(3600000L, config.getSessionMinMs(), "60 minutes should be 3,600,000ms");
    }
}
