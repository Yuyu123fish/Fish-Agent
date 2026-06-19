package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.config.MemoryProperties;
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
import static org.assertj.core.api.Assertions.tuple;
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
        verify(l1).save("sid", l2Snapshot);
    }

    @Test
    void loadForTurnReturnsRecentWindowForColdSessionAtThresholdWithoutSynchronousCompression() {
        List<ChatMessageDTO> fullHistory = messages(6);
        when(l1.load("sid")).thenReturn(emptySnapshot());
        when(l2.load(7L, "sid")).thenReturn(emptySnapshot());

        ShortTermMemoryService.ShortTermMemoryLoadResult result =
                service.loadForTurnWithMetadata(7L, "sid", () -> fullHistory);

        assertThat(result.compressedOnColdPath()).isFalse();
        assertThat(result.snapshot().summary()).isEmpty();
        assertThat(result.snapshot().recentMessages())
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("message-4", "message-5", "message-6");
        verify(l1).save("sid", result.snapshot());
        verify(l2).save(7L, "sid", result.snapshot());
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
        verify(l1).save("sid", result);
        verify(l2).save(7L, "sid", result);
    }

    @Test
    void appendTurnToL1PreservesSummaryAndTrimsWindow() {
        when(l1.load("sid")).thenReturn(new ShortTermMemorySnapshot("summary", new ArrayList<>(messages(3))));

        service.appendTurnToL1("sid", msg("user", "u4"), msg("assistant", "a4"));

        ArgumentCaptor<ShortTermMemorySnapshot> snapshotCaptor = ArgumentCaptor.forClass(ShortTermMemorySnapshot.class);
        verify(l1).save(eq("sid"), snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().summary()).contains("summary");
        assertThat(snapshotCaptor.getValue().recentMessages())
                .extracting(ChatMessageDTO::getRole, ChatMessageDTO::getContent)
                .containsExactly(
                        tuple("user", "message-3"),
                        tuple("user", "u4"),
                        tuple("assistant", "a4"));
    }

    @Test
    void shouldLoadFullHistoryForMaintenanceSkipsWhenWindowIsFarBelowThreshold() {
        when(l1.load("sid")).thenReturn(new ShortTermMemorySnapshot("", List.of(msg("user", "u1"))));

        assertThat(service.shouldLoadFullHistoryForMaintenance("sid")).isFalse();
    }

    @Test
    void shouldLoadFullHistoryForMaintenanceLoadsWhenLegacySummaryHasNoCursorOrWindowApproachesThreshold() {
        when(l1.load("with-summary")).thenReturn(new ShortTermMemorySnapshot("summary", List.of()));
        when(l1.load("near-threshold")).thenReturn(new ShortTermMemorySnapshot("", messages(3)));

        assertThat(service.shouldLoadFullHistoryForMaintenance("with-summary")).isTrue();
        assertThat(service.shouldLoadFullHistoryForMaintenance("near-threshold")).isTrue();
    }

    @Test
    void shouldLoadFullHistoryForMaintenanceUsesCompressedCursorForStructuredSummary() {
        long cursor = 1_000;
        ShortTermMemorySnapshot noNewMessages = new ShortTermMemorySnapshot(
                new StructuredSummary(List.of(new TopicSegment("topic", "ACTIVE", "summary")),
                        java.util.Map.of(), List.of(), new UserSignals("", "", List.of())),
                List.of(messageAt("user", "old", 900), messageAt("assistant", "covered", 1_000)),
                List.of(),
                1,
                cursor
        );
        ShortTermMemorySnapshot enoughNewMessages = new ShortTermMemorySnapshot(
                noNewMessages.structuredSummary(),
                List.of(messageAt("user", "new-1", 1_001), messageAt("assistant", "new-2", 1_002)),
                List.of(),
                1,
                cursor
        );
        when(l1.load("no-new")).thenReturn(noNewMessages);
        when(l1.load("enough-new")).thenReturn(enoughNewMessages);

        assertThat(service.shouldLoadFullHistoryForMaintenance("no-new")).isFalse();
        assertThat(service.shouldLoadFullHistoryForMaintenance("enough-new")).isTrue();
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

    private static ChatMessageDTO messageAt(String role, String content, long createdAt) {
        return new ChatMessageDTO(role, content, createdAt);
    }
}
