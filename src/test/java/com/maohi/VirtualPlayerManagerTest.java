package com.maohi;

import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class VirtualPlayerManagerTest {
    private VirtualPlayerManager vpm;

    @BeforeEach
    public void setup() {
        vpm = new VirtualPlayerManager(null);
    }

    @Test
    public void testVirtualPlayerDetection() {
        UUID uuid = UUID.randomUUID();
        // 初始状态不应该是假人
        assertFalse(vpm.isVirtualPlayer(uuid));
    }

    @Test
    public void testStatusSummary() {
        String summary = vpm.getStatusSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("在线:"));
    }
}
