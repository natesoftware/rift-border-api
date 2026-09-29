package com.natesoftware.riftborder.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Geometry and builder behaviour that needs no spawn - the parts of GameBorder reachable with only a mocked Plugin and World.
class GameBorderGeometryTest {

    private Plugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        plugin = TestMocks.plugin(new TestMocks.FakeScheduler());
        world = TestMocks.world();
    }

    @Test
    void notActiveAndNoCeilingOrFloorBeforeSpawn() {
        GameBorder border = new GameBorder(plugin, world, 0, 64, 0);
        assertFalse(border.isActive());
        assertFalse(border.hasHeightLimit());
        assertFalse(border.hasMinHeight());
        assertEquals(GameBorder.NO_HEIGHT_LIMIT, border.getMaxHeight());
        assertEquals(GameBorder.NO_MIN_HEIGHT, border.getMinHeight());
    }

    @Test
    void isOutsideIsAStrictCircleTest() {
        GameBorder border = new GameBorder(plugin, world, 10, 64, -5);
        border.setPosition(10, -5, 50);
        assertFalse(border.isOutside(10, -5));
        assertFalse(border.isOutside(60, -5));      // exactly on the edge counts as inside
        assertFalse(border.isOutside(10 + 30, -5 + 40));  // 3-4-5 triangle, on the edge
        assertTrue(border.isOutside(60.01, -5));
        assertTrue(border.isOutside(10 + 30, -5 + 40.01));
    }

    @Test
    void heightChecksUseTheConfiguredBounds() {
        GameBorder border = new GameBorder(plugin, world, 0, 64, 0);
        border.setPosition(0, 0, 50, 100, 20);
        assertTrue(border.hasHeightLimit());
        assertTrue(border.hasMinHeight());
        assertTrue(border.isAboveHeight(100.5));
        assertFalse(border.isAboveHeight(100));
        assertTrue(border.isBelowMinHeight(19.5));
        assertFalse(border.isBelowMinHeight(20));
    }

    @Test
    void anchorYDefaultsToJustOutsideTheBuildLimit() {
        assertEquals(320, new GameBorder(plugin, world, 0, 64, 0).anchorY);

        World shortWorld = mock(World.class);
        when(shortWorld.getMaxHeight()).thenReturn(256);
        assertEquals(256, new GameBorder(plugin, shortWorld, 0, 64, 0).anchorY);

        World tallWorld = mock(World.class);
        when(tallWorld.getMaxHeight()).thenReturn(2032);
        assertEquals(2032, new GameBorder(plugin, tallWorld, 0, 64, 0).anchorY);
    }

    // Callers built against 4.0 and earlier still call withGrid, and it must neither throw nor change anything.
    @Test
    @SuppressWarnings("removal")
    void theDeprecatedGridBuilderIsANoOp() {
        GameBorder border = new GameBorder(plugin, world, 0, 64, 0);
        assertSame(border, border.withGrid(0, -1));
        assertSame(border, border.withGrid(80, 1200));
        assertEquals(320, border.anchorY);
    }

    @Test
    void aViewerGetsAFreshDisplayOnlyPastTheReanchorDistance() {
        double r = BorderRenderer.REANCHOR_DISTANCE;
        assertFalse(BorderRenderer.needsNewAnchor(0, 0, 0, 0));
        assertFalse(BorderRenderer.needsNewAnchor(0, 0, r, 0), "exactly at the distance keeps the display");
        assertTrue(BorderRenderer.needsNewAnchor(0, 0, r + 0.01, 0));
        // horizontal distance only: a diagonal walk counts both axes
        assertFalse(BorderRenderer.needsNewAnchor(100, -50, 100 + 22, -50 + 22));
        assertTrue(BorderRenderer.needsNewAnchor(100, -50, 100 + 23, -50 + 23));
    }

    @Test
    void everyBorderGetsItsOwnId() {
        GameBorder a = new GameBorder(plugin, world, 0, 64, 0);
        GameBorder b = new GameBorder(plugin, world, 0, 64, 0);
        assertFalse(a.id.equals(b.id));
    }

    @Test
    void theShaderWallIsOffUntilDeclaredAndTheBuilderChains() {
        GameBorder border = new GameBorder(plugin, world, 0, 64, 0);
        assertFalse(border.shaderWall);
        assertSame(border, border.withShaderWall());
        assertTrue(border.shaderWall);
    }

    @Test
    void theWallColourDefaultsToThePackAquaForBothWallStyles() {
        assertEquals(Color.fromRGB(0x55, 0xFF, 0xFF), BorderTheme.DEFAULT_WALL_COLOR);
        assertEquals(BorderTheme.DEFAULT_WALL_COLOR, new BorderTheme() {}.wallColor());
        // a border given no theme draws the default
        assertEquals(BorderTheme.DEFAULT_WALL_COLOR, new GameBorder(plugin, world, 0, 64, 0).wallColor());
    }

    @Test
    void aCustomWallColourPassesThroughAndANullAnswerFallsBackToTheDefault() {
        GameBorder border = new GameBorder(plugin, world, 0, 64, 0).withTheme(new BorderTheme() {
            @Override
            public Color wallColor() {
                return Color.RED;
            }
        });
        assertEquals(Color.RED, border.wallColor());

        border.withTheme(new BorderTheme() {
            @Override
            public Color wallColor() {
                return null;
            }
        });
        assertEquals(BorderTheme.DEFAULT_WALL_COLOR, border.wallColor());
    }
}
