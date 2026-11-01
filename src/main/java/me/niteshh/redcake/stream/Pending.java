package me.niteshh.redcake.stream;

/**
 * One delivered but not yet acknowledged entry of a consumer group: who has it, when it was last handed out and how
 * many times. The same object sits in the group's pending list and in the owner's, so changing the owner is one
 * update. Changed only by {@link Stream}.
 */
public final class Pending {

    Consumer consumer;
    long deliveryTime;
    long deliveryCount;

    Pending(Consumer consumer, long deliveryTime, long deliveryCount) {
        this.consumer = consumer;
        this.deliveryTime = deliveryTime;
        this.deliveryCount = deliveryCount;
    }

    public Consumer consumer() {
        return consumer;
    }

    /** Epoch milliseconds of the last time the entry was delivered or claimed. */
    public long deliveryTime() {
        return deliveryTime;
    }

    /** How many times the entry was delivered. */
    public long deliveryCount() {
        return deliveryCount;
    }
}
