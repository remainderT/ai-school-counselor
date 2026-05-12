package org.buaa.rag.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DashscopeClientTest {

    @Test
    void resolveDeltaHandlesIncrementalChunks() {
        StringBuilder emitted = new StringBuilder("已为你查询到");

        String delta = DashscopeClient.resolveDelta(emitted, " 2026学年");

        assertEquals(" 2026学年", delta);
    }

    @Test
    void resolveDeltaHandlesCumulativeChunks() {
        StringBuilder emitted = new StringBuilder("已为你查询到");

        String delta = DashscopeClient.resolveDelta(emitted, "已为你查询到 2026学年");

        assertEquals(" 2026学年", delta);
    }

    @Test
    void resolveDeltaRemovesOverlappedPrefixChunks() {
        StringBuilder emitted = new StringBuilder("已为你查询到 2026学年春季学期");

        String delta = DashscopeClient.resolveDelta(emitted, "学期第1周的课表");

        assertEquals("第1周的课表", delta);
    }

    @Test
    void resolveDeltaKeepsWhitespaceAndMarkdownOnlyChunks() {
        StringBuilder emitted = new StringBuilder("以下课程");

        String delta = DashscopeClient.resolveDelta(emitted, "\n\n- **");

        assertEquals("\n\n- **", delta);
    }

    @Test
    void resolveDeltaRemovesSingleCharacterOverlapWhenChunkHasNewContent() {
        StringBuilder emitted = new StringBuilder("你");

        String delta = DashscopeClient.resolveDelta(emitted, "你选");

        assertEquals("选", delta);
    }

    @Test
    void resolveDeltaDoesNotDropAFullyOverlappedChunkWithNoNewContent() {
        StringBuilder emitted = new StringBuilder("哈哈");

        String delta = DashscopeClient.resolveDelta(emitted, "哈");

        assertEquals("哈", delta);
    }
}
