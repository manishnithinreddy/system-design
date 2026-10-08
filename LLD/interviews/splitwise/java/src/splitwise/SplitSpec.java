package splitwise;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * How an expense is divided. A closed set of variants: "sealed" means only these four exist,
 * so a switch over them is checked by the compiler for completeness.
 */
public sealed interface SplitSpec permits SplitSpec.Equal, SplitSpec.Exact, SplitSpec.Percent, SplitSpec.Shares {

    /** Everyone pays the same (remainder paise spread deterministically). */
    record Equal(List<String> userIds) implements SplitSpec {
        public Equal { userIds = List.copyOf(userIds); }
    }

    /** Exact amounts in paise; must add up to the total. */
    record Exact(Map<String, Long> paise) implements SplitSpec {
        public Exact { paise = Map.copyOf(paise); }
    }

    /** Percentages; must add up to 100. */
    record Percent(Map<String, BigDecimal> percents) implements SplitSpec {
        public Percent { percents = Map.copyOf(percents); }
    }

    /** Ratios, e.g. adults 2, kids 1. */
    record Shares(Map<String, Integer> shares) implements SplitSpec {
        public Shares { shares = Map.copyOf(shares); }
    }
}
