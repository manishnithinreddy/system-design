package booking;

import java.util.Set;

public record Room(String id, String building, int capacity, Set<String> features) {
    public Room {
        features = Set.copyOf(features);
    }

    public boolean matches(int minCapacity, Set<String> required) {
        return capacity >= minCapacity && features.containsAll(required);
    }
}
