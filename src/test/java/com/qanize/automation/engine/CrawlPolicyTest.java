package com.qanize.automation.engine;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The crawl limits, and the guarantee that an impossible one cannot be built.
 *
 * <p>No browser, no network and no crawl: a policy is a plain value, and this is what lets the
 * limits be trusted everywhere else without re-checking them.
 */
@Tag("unit")
class CrawlPolicyTest {

    // --- the defaults --------------------------------------------------------------------------

    @Test
    void theDefaultsAreTenPagesAndThreeLevelsDeep() {
        CrawlPolicy policy = CrawlPolicy.defaults();

        assertEquals(10, policy.maxPages(), "the agreed default page budget");
        assertEquals(10, CrawlPolicy.DEFAULT_MAX_PAGES);
        assertEquals(3, policy.maxDepth());
        assertEquals(3, CrawlPolicy.DEFAULT_MAX_DEPTH);
    }

    @Test
    void thePolicyExposesOnlyTheTwoLimitsWeActuallySupport() {
        // Origin is not a setting: a crawl never leaves the start URL's origin, so there is no
        // knob here that could turn that off. Guarded by name so re-adding one is a deliberate act.
        List<String> accessors = Arrays.stream(CrawlPolicy.class.getRecordComponents())
            .map(RecordComponent::getName).toList();

        assertEquals(List.of("maxPages", "maxDepth"), accessors,
            "the public shape of a policy: " + accessors);
        assertEquals(0, Arrays.stream(CrawlPolicy.class.getDeclaredClasses()).count(),
            "no nested scope enum remains");
    }

    // --- invalid values fail clearly -----------------------------------------------------------

    @Test
    void aPageBudgetBelowOneIsRejectedBecauseTheStartPageAlreadyCountsAsOne() {
        for (int impossible : new int[] {0, -1, Integer.MIN_VALUE}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new CrawlPolicy(impossible, 3),
                "maxPages=" + impossible + " must be rejected");
            // The message has to name the field and say why, so a misconfiguration is actionable
            // without reading the source.
            assertTrue(failure.getMessage().contains("maxPages"), failure.getMessage());
            assertTrue(failure.getMessage().contains("start page"), failure.getMessage());
            assertTrue(failure.getMessage().contains(String.valueOf(impossible)),
                "the offending value is quoted back: " + failure.getMessage());
        }
    }

    @Test
    void aNegativeDepthIsRejectedAndZeroIsNot() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new CrawlPolicy(10, -1));
        assertTrue(failure.getMessage().contains("maxDepth"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-1"), failure.getMessage());

        // Zero is meaningful rather than invalid: crawl the start page and follow nothing.
        assertEquals(0, new CrawlPolicy(10, 0).maxDepth());
    }

    @Test
    void oneIsTheSmallestUsablePageBudget() {
        CrawlPolicy policy = new CrawlPolicy(1, 0);

        assertEquals(1, policy.maxPages(), "just the start page is a legitimate crawl");
    }

    // --- the withers stay validated ------------------------------------------------------------

    @Test
    void aWitherKeepsTheOtherFieldsAndStillValidates() {
        CrawlPolicy widened = CrawlPolicy.defaults().withMaxPages(50);
        assertEquals(50, widened.maxPages());
        assertEquals(3, widened.maxDepth(), "the untouched fields survive");

        CrawlPolicy deeper = CrawlPolicy.defaults().withMaxDepth(7);
        assertEquals(7, deeper.maxDepth());
        assertEquals(10, deeper.maxPages());

        // A wither is a constructor, so it cannot be used to sneak past validation.
        assertThrows(IllegalArgumentException.class, () -> CrawlPolicy.defaults().withMaxPages(0));
        assertThrows(IllegalArgumentException.class, () -> CrawlPolicy.defaults().withMaxDepth(-2));
    }

    @Test
    void twoPoliciesWithTheSameLimitsAreEqual() {
        assertEquals(CrawlPolicy.defaults(), new CrawlPolicy(10, 3));
    }
}
