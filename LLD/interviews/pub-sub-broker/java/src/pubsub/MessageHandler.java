package pubsub;

import java.util.function.Consumer;

/** The Observer: the broker calls it for each message. Throwing counts as a nack. */
@FunctionalInterface
public interface MessageHandler {
    void onMessage(Delivery delivery) throws Exception;

    /** The common case: run the callback, ack if it returns normally (an exception means nack). */
    static MessageHandler autoAck(Consumer<Message> callback) {
        return d -> {
            callback.accept(d.message());
            d.ack();
        };
    }
}
