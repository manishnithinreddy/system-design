package pubsub;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One published message. Immutable: every subscription gets the SAME object, so nobody may change it.
 * {@code key} may be null; when set, all messages with that key are delivered in order (see {@link Lane}).
 */
public record Message(String id, String topic, String key, String payload, Instant publishedAt,
                      Map<String, String> headers) {
    public Message {
        headers = Map.copyOf(headers);
    }

    /** A copy for another topic (used for dead-lettering), with extra headers explaining why. */
    Message copyTo(String newTopic, Map<String, String> extraHeaders) {
        Map<String, String> h = new HashMap<>(headers);
        h.putAll(extraHeaders);
        return new Message(id, newTopic, key, payload, publishedAt, h);
    }
}
