package fs;

import java.util.List;

/** Who is calling. The first group is the primary group, given to the files this user creates. */
public record User(String name, List<String> groups) {
    public static final User ROOT = new User("root", List.of("root"));

    public User {
        groups = List.copyOf(groups);
        if (groups.isEmpty()) throw new IllegalArgumentException("a user needs at least one group");
    }

    public static User of(String name, String... groups) { return new User(name, List.of(groups)); }

    public String primaryGroup() { return groups.get(0); }

    /** Decision for this design: root skips every permission check (Linux root skips almost all). */
    public boolean isRoot() { return name.equals("root"); }
}
