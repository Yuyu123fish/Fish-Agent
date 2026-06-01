package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.memory.dto.MemoryCompressionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortTermMemoryServiceTest {

    @Mock
    ShortTermMemoryStore l1;
    @Mock
    ShortTermSnapshotStore l2;
    @Mock
    MemoryCompressionService compression;

    MemoryProperties properties;
    ShortTermMemoryService service;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setShortTermWindowSize(3);
        properties.setSummaryTriggerThreshold(5);
        service = new ShortTermMemoryService(l1, l2, compression, properties);
    }

    @Test
    void loadForTurnReturnsL1SnapshotWithoutCallingL2OrL3WhenL1Hits() {
        ShortTermMemorySnapshot l1Snapshot = new ShortTermMemorySnapshot("summary", List.of(msg("user", "hot")));
        when(l1.load("sid")).thenReturn(l1Snapshot);
        Supplier<List<ChatMessageDTO>> fullLoader = () -> {
            throw new AssertionError("L3 should not be loaded when L1 hits");
        };

        ShortTermMemorySnapshot result = service.loadForTurn(7L, "sid", fullLoader);

        assertThat(result).isSameAs(l1Snapshot);
        verify(l2, never()).load(7L, "sid");
    }

    @Test
    void loadForTurnRefillsL1WhenL2Hits() {
        when(l1.load("sid")).thenReturn(emptySnapshot());
        ShortTermMemorySnapshot l2Snapshot = new ShortTermMemorySnapshot("from-l2", List.of(msg("assistant", "cached")));
        when(l2.load(7L, "sid")).thenReturn(l2Snapshot);

        ShortTermMemorySnapshot result = service.loadForTurn(7L, "sid", List::of);

        assertThat(result).isSameAs(l2Snapshot);
        verify(l1).save("sid", "from-l2", l2Snapshot.recentMessages());
    }

    @Test
    void loadForTurnRecomputesColdSessionAndPersistsSnapshotWhenHistoryReachesThreshold() {
        List<ChatMessageDTO> fullHistory = messages(6);
        ShortTermMemorySnapshot recomputed = new ShortTermMemorySnapshot("new-summary", List.of(msg("assistant", "latest")));
        when(l1.load("sid")).thenReturn(emptySnapshot(), recomputed);
        when(l2.load(7L, "sid")).thenReturn(emptySnapshot());

        ShortTermMemorySnapshot result = service.loadForTurn(7L, "sid", () -> fullHistory);

        assertThat(result).isSameAs(recomputed);
        ArgumentCaptor<MemoryCompressionRequest> requestCaptor = ArgumentCaptor.forClass(MemoryCompressionRequest.class);
        verify(compression).compress(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getSessionId()).isEqualTo("sid");
        assertThat(requestCaptor.getValue().getChatHistory()).isSameAs(fullHistory);
        verify(l2).save(7L, "sid", recomputed);
    }

    @Test
    void loadForTurnFallsBackToRecentWindowForColdSessionBelowThreshold() {
        List<ChatMessageDTO> fullHistory = messages(4);
        when(l1.load("sid")).thenReturn(emptySnapshot());
        when(l2.load(7L, "sid")).thenReturn(emptySnapshot());

        ShortTermMemorySnapshot result = service.loadForTurn(7L, "sid", () -> fullHistory);

        assertThat(result.summary()).isEmpty();
        assertThat(result.recentMessages())
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("message-2", "message-3", "message-4");
        verify(l1).save(eq("sid"), eq(""), eq(result.recentMessages()));
        verify(l2).save(7L, "sid", result);
    }

    @Test
    void appendTurnToL1PreservesSummaryAndTrimsWindow() {
        when(l1.load("sid")).thenReturn(new ShortTermMemorySnapshot("summary", new ArrayList<>(messages(3))));

        service.appendTurnToL1("sid", msg("user", "u4"), msg("assistant", "a4"));

        ArgumentCaptor<List<ChatMessageDTO>> windowCaptor = ArgumentCaptor.forClass(List.class);
        verify(l1).save(eq("sid"), eq("summary"), windowCaptor.capture());
        assertThat(windowCaptor.getValue())
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("message-3", "u4", "a4");
    }

    @Test
    void refreshSnapshotFromL1CopiesNonEmptySnapshotToL2() {
        ShortTermMemorySnapshot snapshot = new ShortTermMemorySnapshot("summary", List.of(msg("user", "u")));
        when(l1.load("sid")).thenReturn(snapshot);

        service.refreshSnapshotFromL1(7L, "sid");

        verify(l2).save(7L, "sid", snapshot);
    }

    @Test
    void clearDeletesBothL1AndL2WhenSnapshotIsEnabled() {
        service.clear(7L, "sid");

        verify(l1).clear("sid");
        verify(l2).clear(7L, "sid");
    }

    private static ShortTermMemorySnapshot emptySnapshot() {
        return new ShortTermMemorySnapshot("", List.of());
    }

    private static List<ChatMessageDTO> messages(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> msg("user", "message-" + i))
                .toList();
    }

    private static ChatMessageDTO msg(String role, String content) {
        return ChatMessageDTO.of(role, content);
    }
}
