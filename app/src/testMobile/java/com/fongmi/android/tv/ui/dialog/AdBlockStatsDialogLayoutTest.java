package com.fongmi.android.tv.ui.dialog;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class AdBlockStatsDialogLayoutTest {

    @Test
    public void statsDialogUsesNearFullScreenTabbedRoot() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));
            int rootStart = layout.indexOf("<androidx.appcompat.widget.LinearLayoutCompat");
            String root = layout.substring(rootStart, layout.indexOf('>', rootStart) + 1);

            assertTrue(flavor + " statistics dialog should use the available height",
                    root.contains("android:layout_height=\"match_parent\""));
            assertTrue(layout.contains("android:id=\"@+id/statsTabs\""));
            assertTrue(layout.contains("android:id=\"@+id/overviewPage\""));
            assertTrue(layout.contains("android:id=\"@+id/sitePage\""));
            assertTrue(layout.contains("android:id=\"@+id/rulePage\""));
            assertTrue(layout.contains("android:id=\"@+id/pipelinePage\""));
        }
    }

    @Test
    public void statsDialogUsesFixedSafetyMarginsInsteadOfScreenPercentages() throws Exception {
        Path root = findRepositoryRoot();
        String mobile = read(root.resolve(Path.of("app", "src", "mobile", "java", "com", "fongmi", "android", "tv", "ui", "dialog", "AdBlockStatsDialog.java")));
        String leanback = read(root.resolve(Path.of("app", "src", "leanback", "java", "com", "fongmi", "android", "tv", "ui", "dialog", "AdBlockStatsDialog.java")));

        assertTrue(mobile.contains("metrics.widthPixels - horizontalMargin * 2"));
        assertTrue(mobile.contains("metrics.heightPixels - verticalMargin * 2"));
        assertTrue(mobile.contains("window.getDecorView().setPadding(0, 0, 0, 0)"));
        assertTrue(mobile.contains("params.height = height"));
        assertTrue(mobile.contains("binding.getRoot().setMinimumHeight(height)"));
        assertTrue(leanback.contains("ResUtil.getScreenWidth(activity) - horizontalMargin * 2"));
        assertTrue(leanback.contains("binding.getRoot().setMinimumHeight(height)"));
        assertTrue(leanback.contains("ResUtil.getScreenHeight(activity) - verticalMargin * 2"));
        assertTrue(!mobile.contains("metrics.widthPixels * 0.94f"));
        assertTrue(!mobile.contains("metrics.heightPixels * 0.92f"));
        assertTrue(!leanback.contains("ResUtil.getScreenWidth(activity) * 0.94f"));
        assertTrue(!leanback.contains("ResUtil.getScreenHeight(activity) * 0.92f"));
    }

    @Test
    public void statsDialogKeepsAllThreeCoreMetrics() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));

            assertTrue(layout.contains("android:id=\"@+id/totalBlocked\""));
            assertTrue(layout.contains("android:id=\"@+id/aiFeedbackCount\""));
            assertTrue(layout.contains("android:id=\"@+id/aiSuccessRate\""));
        }
    }

    @Test
    public void statsDialogDisablesOverscrollAndRemovesGrayCards() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));

            assertTrue(layout.contains("android:overScrollMode=\"never\""));
            assertTrue(!layout.contains("MaterialCardView"));
            assertTrue(!layout.contains("app:cardBackgroundColor=\"@color/black_10\""));
        }
    }

    @Test
    public void statsDialogIncludesSiteDimensionOverview() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));

            assertTrue(layout.contains("android:id=\"@+id/siteCount\""));
            assertTrue(layout.contains("android:id=\"@+id/topSite\""));
            assertTrue(layout.contains("android:id=\"@+id/topSiteShare\""));
        }
    }

    @Test
    public void statsDialogKeepsAllThreeRankingDimensionsInTheScrollableContent() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));

            assertTrue(layout.contains("android:id=\"@+id/siteRankRecycler\""));
            assertTrue(layout.contains("android:id=\"@+id/ruleRankRecycler\""));
            assertTrue(layout.contains("android:id=\"@+id/pipelineRankRecycler\""));
        }
    }

    @Test
    public void mobileStatsDialogUsesReadableTextOnWhiteSurface() throws Exception {
        Path root = findRepositoryRoot();
        String dialog = read(root.resolve(Path.of("app", "src", "mobile", "res", "layout", "dialog_ad_block_stats.xml")));
        String item = read(root.resolve(Path.of("app", "src", "mobile", "res", "layout", "adapter_ad_stats_item.xml")));

        assertTrue("White dialog surface must not use white primary text",
                !dialog.contains("android:textColor=\"@color/white\"")
                        && !item.contains("android:textColor=\"@color/white\""));
        assertTrue("White dialog surface must not use translucent white secondary text",
                !dialog.contains("android:textColor=\"@color/white_50\"")
                        && !item.contains("android:textColor=\"@color/white_50\""));
    }

    @Test
    public void simplifiedChineseStatsDialogLocalizesPipelineRankingTitle() throws Exception {
        String strings = read(findRepositoryRoot().resolve(Path.of("app", "src", "main", "res", "values-zh-rCN", "strings.xml")));

        assertTrue("The pipeline ranking title should be localized for the Chinese TV/mobile UI",
                strings.contains("<string name=\"ad_pipeline_rank\">播放链路</string>"));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(Path.of("app", "src", "mobile", "res", "layout", "dialog_ad_block_stats.xml")))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
