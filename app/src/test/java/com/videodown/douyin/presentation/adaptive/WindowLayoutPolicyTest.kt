package com.videodown.douyin.presentation.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutPolicyTest {
    @Test fun widthBreaksAtMaterialThreeBreakpoints() {
        assertEquals(WindowWidth.COMPACT, WindowLayoutPolicy.classify(0))
        assertEquals(WindowWidth.COMPACT, WindowLayoutPolicy.classify(480))
        assertEquals(WindowWidth.COMPACT, WindowLayoutPolicy.classify(599))
        assertEquals(WindowWidth.MEDIUM, WindowLayoutPolicy.classify(600))
        assertEquals(WindowWidth.MEDIUM, WindowLayoutPolicy.classify(700))
        assertEquals(WindowWidth.MEDIUM, WindowLayoutPolicy.classify(839))
        assertEquals(WindowWidth.EXPANDED, WindowLayoutPolicy.classify(840))
        assertEquals(WindowWidth.EXPANDED, WindowLayoutPolicy.classify(1200))
    }

    @Test fun navigationUsesBarOnCompactAndRailOnMediumAndExpanded() {
        assertEquals(NavigationLayout.BOTTOM_BAR, WindowLayoutPolicy.navigation(WindowWidth.COMPACT, 4))
        assertEquals(NavigationLayout.RAIL, WindowLayoutPolicy.navigation(WindowWidth.MEDIUM, 4))
        assertEquals(NavigationLayout.RAIL, WindowLayoutPolicy.navigation(WindowWidth.EXPANDED, 4))
    }

    @Test fun drawerOnlyForDenseTopLevelSets() {
        assertEquals(NavigationLayout.DRAWER, WindowLayoutPolicy.navigation(WindowWidth.EXPANDED, 7))
        assertEquals(NavigationLayout.DRAWER, WindowLayoutPolicy.navigation(WindowWidth.MEDIUM, 8))
        // 四个一级入口是当前真实密度，不应因为屏宽就换成抽屉。
        assertEquals(NavigationLayout.RAIL, WindowLayoutPolicy.navigation(WindowWidth.EXPANDED, 4))
    }

    @Test fun workspaceWidthOnlyWidensOnExpanded() {
        assertEquals(840, WindowLayoutPolicy.contentMaxWidthDp(ContentWidth.READABLE, WindowWidth.COMPACT))
        assertEquals(840, WindowLayoutPolicy.contentMaxWidthDp(ContentWidth.READABLE, WindowWidth.EXPANDED))
        assertEquals(840, WindowLayoutPolicy.contentMaxWidthDp(ContentWidth.WORKSPACE, WindowWidth.COMPACT))
        assertEquals(840, WindowLayoutPolicy.contentMaxWidthDp(ContentWidth.WORKSPACE, WindowWidth.MEDIUM))
        assertEquals(1040, WindowLayoutPolicy.contentMaxWidthDp(ContentWidth.WORKSPACE, WindowWidth.EXPANDED))
    }

    @Test fun pagePaddingIsSixteenOnCompactAndTwentyFourAbove() {
        assertEquals(16, WindowLayoutPolicy.pagePaddingDp(WindowWidth.COMPACT))
        assertEquals(24, WindowLayoutPolicy.pagePaddingDp(WindowWidth.MEDIUM))
        assertEquals(24, WindowLayoutPolicy.pagePaddingDp(WindowWidth.EXPANDED))
    }

    @Test fun feedColumnsFollowWindowClass() {
        assertEquals(2, WindowLayoutPolicy.feedColumns(WindowWidth.COMPACT))
        assertEquals(3, WindowLayoutPolicy.feedColumns(WindowWidth.MEDIUM))
        assertEquals(4, WindowLayoutPolicy.feedColumns(WindowWidth.EXPANDED))
    }

    @Test fun dualPaneLayoutsOnlyOnExpanded() {
        assertFalse(WindowLayoutPolicy.homeDualPane(WindowWidth.COMPACT))
        assertFalse(WindowLayoutPolicy.homeDualPane(WindowWidth.MEDIUM))
        assertTrue(WindowLayoutPolicy.homeDualPane(WindowWidth.EXPANDED))
        assertFalse(WindowLayoutPolicy.settingsListDetail(WindowWidth.MEDIUM))
        assertTrue(WindowLayoutPolicy.settingsListDetail(WindowWidth.EXPANDED))
    }

    @Test fun listDetailPaneKeepsDetailWideEnough() {
        assertEquals(0, WindowLayoutPolicy.listPaneWidthDp(WindowWidth.COMPACT))
        assertEquals(280, WindowLayoutPolicy.listPaneWidthDp(WindowWidth.MEDIUM))
        assertEquals(320, WindowLayoutPolicy.listPaneWidthDp(WindowWidth.EXPANDED))
        // Medium 的 600dp 窗口扣掉主栏后仍要留得下至少 320dp 详情。
        assertTrue(600 - WindowLayoutPolicy.listPaneWidthDp(WindowWidth.MEDIUM) >= 320)
    }

    @Test fun noFoldKeepsWholeUsableArea() {
        val pane = WindowLayoutPolicy.foldPane(1200, 800, null)
        assertEquals(FoldPane(1200, 800, 0, 0), pane)
    }

    @Test fun nonSeparatingFoldDoesNotMoveContent() {
        val fold = FoldFeature(separating = false, verticalHinge = true, leftDp = 580, topDp = 0, rightDp = 620, bottomDp = 800)
        assertEquals(FoldPane(1200, 800, 0, 0), WindowLayoutPolicy.foldPane(1200, 800, fold))
    }

    @Test fun bookPosturePutsContentInTheWiderHalf() {
        // 纵向折痕在中间偏左：右半更宽，内容移到右半。
        val fold = FoldFeature(separating = true, verticalHinge = true, leftDp = 500, topDp = 0, rightDp = 540, bottomDp = 800)
        val pane = WindowLayoutPolicy.foldPane(1100, 800, fold)
        assertEquals(560, pane.widthDp)
        assertEquals(540, pane.offsetXDp)
        assertEquals(800, pane.heightDp)

        // 左侧更宽时留在左侧。
        val leftFolded = FoldFeature(separating = true, verticalHinge = true, leftDp = 620, topDp = 0, rightDp = 660, bottomDp = 800)
        val leftPane = WindowLayoutPolicy.foldPane(1100, 800, leftFolded)
        assertEquals(620, leftPane.widthDp)
        assertEquals(0, leftPane.offsetXDp)
    }

    @Test fun tabletopPosturePutsContentInTheTallerHalf() {
        val fold = FoldFeature(separating = true, verticalHinge = false, leftDp = 0, topDp = 480, rightDp = 800, bottomDp = 520)
        val pane = WindowLayoutPolicy.foldPane(800, 1200, fold)
        assertEquals(680, pane.heightDp)
        assertEquals(520, pane.offsetYDp)
        assertEquals(800, pane.widthDp)
    }

    @Test fun degenerateFoldBoundsFallBackToFullArea() {
        val zero = FoldFeature(separating = true, verticalHinge = true, leftDp = 0, topDp = 0, rightDp = 1200, bottomDp = 800)
        assertEquals(FoldPane(1200, 800, 0, 0), WindowLayoutPolicy.foldPane(1200, 800, zero))
    }
}
