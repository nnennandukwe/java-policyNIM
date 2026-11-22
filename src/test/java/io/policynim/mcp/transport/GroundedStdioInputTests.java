package io.policynim.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.function.BiPredicate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedStdioInputTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String MODERN = "2026-07-28";
    private static final String META = "{\"_meta\":{\"" + VERSION + "\":\"" + MODERN + "\"}}";
    private static final String HELLO = """
        {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"client","version":"1"}}""";
    private final List<JsonNode> replies = new ArrayList<>();
    private final List<Object> requests = new ArrayList<>();
    private final List<Object> cancellations = new ArrayList<>();

    @Test
    void selectsFromTheOpeningRequestAndForwardsOnlyTheFirstEightEarlierFrames() throws Exception {
        var prefix = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            prefix.add("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":" + i + "}}\n");
        }
        String open = request(1, "tools/list", META);
        String follow = request(2, "tools/list", META);
        var input = input(String.join("", prefix) + open + follow);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.MODERN);
        assertThat(requests).isEmpty();
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo(String.join("", prefix.subList(0, 8)) + open + follow);
        assertThat(requests).containsExactly(1, 2);
    }

    @Test
    void rejectsInitializeOnTheModernConnectionAndForwardsTheNextRequest() throws Exception {
        String open = request(1, "tools/list", META);
        String follow = request(3, "tools/list", META);
        var input = input(open + request("\"cross-era\"", "initialize", HELLO) + follow);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.MODERN);
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(open + follow);
        assertThat(replies).hasSize(1);
        assertThat(replies.getFirst().required("id").asText()).isEqualTo("cross-era");
        assertThat(replies.getFirst().at("/error/code").asInt()).isEqualTo(-32022);
        assertThat(replies.getFirst().at("/error/data/requested").asText()).isEqualTo("2025-11-25");
        assertThat(replies.getFirst().at("/error/data/supported/0").asText()).isEqualTo(MODERN);
        assertThat(requests).containsExactly(1, 3);
    }

    @Test
    void initializeSelectsLegacyEvenWithAStampAndPreservesTheNextBareRequest() throws Exception {
        String stamped = HELLO.substring(0, HELLO.length() - 1) + ",\"_meta\":{\"" + VERSION + "\":\"" + MODERN + "\"}}";
        String open = request(1, "initialize", stamped);
        String normalized = request(3, "tools/list", "{}");
        var input = input(open + request(2, "tools/list", META) + normalized);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.LEGACY);
        byte[] first = new byte[4096];
        int count = input.read(first);
        assertThat(new String(first, 0, count, StandardCharsets.UTF_8)).isEqualTo(open);
        input.initialized(1, true);
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(normalized);
        assertThat(replies.getFirst().get("id").intValue()).isEqualTo(2);
        assertThat(replies.getFirst().at("/error/code").intValue()).isEqualTo(-32600);
        assertThat(requests).containsExactly(1, 3);
    }

    @Test
    void progressOnlyMetadataSelectsLegacyAndInvalidInitializeCanBeRetried() throws Exception {
        String open = request(1, "tools/list", "{\"_meta\":{\"progressToken\":1}}");
        String valid = request(3, "initialize", HELLO);
        var input = input(open + request(2, "initialize", "{}") + valid);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.LEGACY);
        byte[] frame = new byte[4096];
        int n = input.read(frame);
        assertThat(new String(frame, 0, n, StandardCharsets.UTF_8)).isEqualTo(valid);
        assertThat(requests).containsExactly(3);
        assertThat(replies).extracting(r -> r.at("/error/code").intValue()).containsExactly(-32602, -32602);
        input.initialized(3, true);
        assertThat(input.read()).isEqualTo(-1);
    }

    @Test
    void eofBeforeARequestAndMalformedLinesDoNotChooseAnEra() throws Exception {
        assertThat(input("{\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n").select()).isNull();
        String open = request(1, "tools/list", META);
        var input = input("{\n" + open + "{\n" + open);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.MODERN);
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(open + open);
        assertThat(replies).isEmpty();
    }

    @Test
    void retainsAFrameOfExactlyFourMiBAndDropsTheNextOversizedFrameThroughItsTerminator() throws Exception {
        String open = request(1, "tools/list", META).stripTrailing();
        String nonAscii = open.replace("\"tools/list\"", "\"é/list\"");
        String full = nonAscii + " ".repeat(GroundedStdioFrames.MAX_BYTES - nonAscii.getBytes(StandardCharsets.UTF_8).length);
        String follow = request(2, "tools/list", META);
        var input = input(full + "\r\n" + full + " \n" + follow.stripTrailing());
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.MODERN);
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(full + "\n" + follow);
        assertThat(requests).containsExactly(1, 2);
    }

    @Test
    void malformedUtf8AndUtf16AreDroppedBeforeOrAfterTheOpeningFrame() throws Exception {
        var wire = new ByteArrayOutputStream();
        var invalid = request(1, "X", "{}").getBytes(StandardCharsets.UTF_8);
        invalid[new String(invalid, StandardCharsets.UTF_8).indexOf('X')] = (byte) 0xff;
        String open = request(2, "tools/list", META);
        wire.write(invalid);
        wire.write(open.stripTrailing().getBytes(StandardCharsets.UTF_16));
        wire.write('\n');
        wire.write(open.getBytes(StandardCharsets.UTF_8));
        wire.write(invalid);
        var input = input(wire.toByteArray(), (method, id) -> requests.add(id), true);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.MODERN);
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(open);
        assertThat(requests).containsExactly(2);
    }

    @Test
    void anEarlyInitializedNotificationAllowsLegacyToolsAndModernCancellationOnlyTracksForwardedIds() throws Exception {
        String ready = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
        String list = request(1, "tools/list", "{}");
        var legacy = input(ready + list);
        assertThat(legacy.select()).isEqualTo(GroundedStdioInput.Era.LEGACY);
        assertThat(new String(legacy.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(ready + list);
        String modern = request(2, "tools/list", META);
        String cancel = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":2}}\n";
        var input = input(modern + cancel);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.MODERN);
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(modern + cancel);
        assertThat(cancellations).containsExactly(2);
    }

    @Test
    void disabledToolsReachTheSdkMethodNotFoundHandlerAndActiveDuplicateIdsCannotReplacePendingWork() throws Exception {
        String list = request(1, "tools/list", "{}");
        String duplicate = request(1, "initialize", HELLO);
        String other = request(2, "unknown", "{}");
        var input = input((list + duplicate + other).getBytes(StandardCharsets.UTF_8),
            (method, id) -> !requests.contains(id) && requests.add(id), false);
        assertThat(input.select()).isEqualTo(GroundedStdioInput.Era.LEGACY);
        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(list + other);
        assertThat(requests).containsExactly(1, 2);
    }

    private GroundedStdioInput input(String text) {
        return input(text.getBytes(StandardCharsets.UTF_8), (method, id) -> requests.add(id), true);
    }

    private GroundedStdioInput input(byte[] bytes, BiPredicate<String, Object> onRequest, boolean toolsEnabled) {
        return new GroundedStdioInput(new ByteArrayInputStream(bytes), line -> replies.add(JSON.readTree(line)),
            onRequest, cancellations::add, toolsEnabled, Duration.ofSeconds(2));
    }

    private static String request(Object id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}\n";
    }
}
