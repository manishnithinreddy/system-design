package pubsub;

/** How many times a subscriber may see a message if something fails. */
public enum DeliveryMode {
    /** Counted as done the moment it is handed to the handler. A crash or exception loses it. */
    AT_MOST_ONCE,
    /** Done only when the handler calls ack(). A nack, an exception or a missed ack deadline redelivers it. */
    AT_LEAST_ONCE
}
