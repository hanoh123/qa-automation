package com.qanize.automation.reporting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.qanize.automation.evidence.EvidenceScope;
import com.qanize.automation.model.TestedItem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published detail budget, on a crawl that visits more than one page.
 *
 * <p>A three-page OrangeHRM run recorded 12, 13 and 13 passing links — all written to the evidence
 * file, all real HTTP requests. The report showed 12 on page one, 8 on page two, and no Links
 * section at all on page three. Nothing had failed and nothing had been skipped for a reason: the
 * detail cap kept the first twenty items of the LINKS type across the whole run, having ordered
 * them by status with no idea which page any of them came from. Page one's twelve and eight of page
 * two's filled the allowance, and everything after that — the rest of page two and the whole of
 * page three — was dropped on the floor.
 *
 * <p>The consequence is worse than a short list. The page card is built from the items a check
 * contributed to that page, so a check that contributes none produces no card, and a selected check
 * disappeared from a crawled page with no explanation anywhere in the report.
 *
 * <p>The budget is per page now. These tests pin that, and pin that a single-page run — which has
 * no page scoping at all — is capped exactly as it always was.
 */
@Tag("unit")
class CrawlDetailCapTest {

    private static final int CAP = RunSummaryFactory.MAX_DETAILED_ITEMS_PER_TYPE;

    // --- the reported defect ----------------------------------------------------------------------

    @Test
    void everyCrawledPageKeepsItsOwnLinksDetail() {
        // The real run's shape: three pages, twelve then thirteen then thirteen passing links.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(1, 12));
        recorded.addAll(links(2, 13));
        recorded.addAll(links(3, 13));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(12, countOn(published, 1), "page 1 kept " + countOn(published, 1));
        assertEquals(13, countOn(published, 2), "page 2 kept " + countOn(published, 2));
        assertEquals(13, countOn(published, 3),
            "page 3 kept " + countOn(published, 3) + " — the page that vanished from the report");
        assertEquals(38, published.size(), "nothing was under any page's allowance, so nothing is cut");
    }

    @Test
    void noSelectedCheckDisappearsFromAPageItRanOn() {
        // The property that matters more than any count. A page whose check produced items must
        // still have items afterwards, because a page card is built from exactly those.
        List<TestedItem> recorded = new ArrayList<>();
        for (int page = 1; page <= 8; page++) {
            recorded.addAll(links(page, 15));
        }

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        for (int page = 1; page <= 8; page++) {
            assertTrue(countOn(published, page) > 0,
                "page " + page + " contributed items and then had none published");
        }
        assertEquals(pagesIn(recorded), pagesIn(published),
            "every page that recorded a Links item still appears in the published detail");
    }

    @Test
    void aPageThatOverflowsIsTrimmedWithoutTakingAnotherPagesAllowance() {
        // One enormous page must not spend the budget the other pages need.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(1, 200));
        recorded.addAll(links(2, 4));
        recorded.addAll(links(3, 4));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, countOn(published, 1), "the overflowing page is trimmed to its allowance");
        assertEquals(4, countOn(published, 2), "and the small pages are untouched");
        assertEquals(4, countOn(published, 3));
    }

    @Test
    void pageOrderIsPreservedInThePublishedDetail() {
        // The report renders pages in crawl order, and the detail must not arrive shuffled.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(1, 3));
        recorded.addAll(links(2, 3));
        recorded.addAll(links(3, 3));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(List.of("page-001", "page-002", "page-003"),
            new ArrayList<>(pagesIn(published)));
    }

    @Test
    void failuresOnALaterPageSurviveAnEarlierPagesVolume() {
        // The worst version of the defect: a busy first page burying a real finding on page three.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(1, 60));
        recorded.addAll(links(2, 60));
        recorded.add(item(3, "LINKS", "link-broken", TestedItem.Status.FAILED));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertTrue(published.stream().anyMatch(i -> "page-003/link-broken".equals(i.id())),
            "the only failure in the run was dropped from the report");
    }

    // --- what must not change ---------------------------------------------------------------------

    @Test
    void aSinglePageRunIsCappedExactlyAsItAlwaysWas() {
        // No page metadata at all, which is every pre-crawler run: one bucket, one allowance.
        List<TestedItem> recorded = new ArrayList<>();
        for (int index = 1; index <= 47; index++) {
            recorded.add(item(0, "LINKS", "link-" + index, TestedItem.Status.PASSED));
        }

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, published.size(), "the legacy cap is unchanged");
    }

    @Test
    void checkTypesKeepSeparateAllowancesOnTheSamePage() {
        // Links being busy must not cost Smoke its record on the same page, and vice versa.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(1, 40));
        recorded.add(item(1, "SMOKE", "smoke-page-1", TestedItem.Status.PASSED));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, countOn(published, 1), "Links is trimmed to its own allowance");
        assertTrue(published.stream().anyMatch(i -> "page-001/smoke-page-1".equals(i.id())),
            "Smoke keeps its record regardless of how many links the page had");
    }

    @Test
    void anUnderBudgetPageIsPublishedInTheOrderItWasRecorded() {
        // Nothing is re-sorted unless a page genuinely overflows, so a skipped link recorded first
        // is still published first.
        List<TestedItem> recorded = List.of(
            item(1, "LINKS", "link-skipped-1", TestedItem.Status.SKIPPED),
            item(1, "LINKS", "link-1", TestedItem.Status.PASSED),
            item(1, "LINKS", "link-2", TestedItem.Status.PASSED));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(
            List.of("page-001/link-skipped-1", "page-001/link-1", "page-001/link-2"),
            published.stream().map(TestedItem::id).toList());
    }

    @Test
    void skippedItemsOnEveryPageSurviveWhenNoPageOverflows() {
        // The excluded links — external, duplicate, fragment — are the explanation for why a page
        // requested fewer destinations than it discovered. Losing them loses the reason.
        List<TestedItem> recorded = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            recorded.add(item(page, "LINKS", "link-skipped-1", TestedItem.Status.SKIPPED));
            recorded.addAll(links(page, 5));
        }

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        for (int page = 1; page <= 3; page++) {
            int pageNumber = page;
            assertTrue(published.stream().anyMatch(i ->
                    i.id().equals(String.format("page-%03d/link-skipped-1", pageNumber))),
                "page " + page + " lost the record explaining an excluded link");
        }
    }

    @Test
    void theRunLevelSignInIsNotAttributedToAnyPage() {
        // Login happens once, before the crawl, and carries no page scope. It must not be pulled
        // into a page's allowance or appear to belong to one.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.add(item(0, "LOGIN", "login-1", TestedItem.Status.PASSED));
        recorded.addAll(links(1, 5));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertTrue(published.stream().anyMatch(i -> "login-1".equals(i.id())));
        assertFalse(published.stream()
                .filter(i -> "login-1".equals(i.id()))
                .anyMatch(i -> i.metadata().containsKey(EvidenceScope.META_ID)),
            "the sign-in belongs to the run, not to a page");
    }

    @Test
    void nothingIsInventedAndNothingIsDuplicated() {
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(1, 30));
        recorded.addAll(links(2, 3));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        Set<String> recordedIds = recorded.stream().map(TestedItem::id).collect(Collectors.toSet());
        assertEquals(published.size(), published.stream().map(TestedItem::id).distinct().count(),
            "an item must not be published twice");
        assertTrue(recordedIds.containsAll(published.stream().map(TestedItem::id).toList()),
            "every published item was actually recorded");
    }

    // --- ordinary passes must not evict the evidence ---------------------------------------------

    @Test
    void twentyThreePassesDoNotEvictTheFourSkipsBesideThem() {
        // The defect, reproduced from run qanize-1790766054652-8e65e2e3: 27 links on one page, 23
        // passing and 4 skipped. The passes filled all twenty places and every skip was dropped --
        // including the two destinations recorded AUTH_REQUIRED, which were the only records in
        // the whole check with anything to say.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(0, 23));
        recorded.add(skip(0, "link-skipped-1", "exclusion", "EXTERNAL"));
        recorded.add(skip(0, "link-skipped-2", "exclusion", "EXTERNAL"));
        recorded.add(skip(0, "link-3", "linkOutcome", "AUTH_REQUIRED"));
        recorded.add(skip(0, "link-8", "linkOutcome", "AUTH_REQUIRED"));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, published.size(), "the allowance is spent in full, and is still twenty");
        assertEquals(4, statusCount(published, TestedItem.Status.SKIPPED),
            "every skipped record survived: " + ids(published));
        assertEquals(16, statusCount(published, TestedItem.Status.PASSED));
        for (String id : List.of("link-skipped-1", "link-skipped-2", "link-3", "link-8")) {
            assertTrue(published.stream().anyMatch(i -> id.equals(i.id())),
                id + " was dropped: " + ids(published));
        }
    }

    @Test
    void failuresStillTakeEveryPlaceTheyNeed() {
        // Unchanged and deliberately not shared: a page of failures publishes failures.
        List<TestedItem> recorded = new ArrayList<>();
        for (int index = 1; index <= 25; index++) {
            recorded.add(item(0, "LINKS", "fail-" + index, TestedItem.Status.FAILED));
        }
        recorded.addAll(links(0, 10));
        recorded.add(skip(0, "link-skipped-1", "exclusion", "EXTERNAL"));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, published.size());
        assertEquals(CAP, statusCount(published, TestedItem.Status.FAILED),
            "failures are still the highest priority and take the whole allowance");
    }

    @Test
    void failuresComeFirstAndTheSkipsStillGetTheirShareOfWhatIsLeft() {
        List<TestedItem> recorded = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            recorded.add(item(0, "LINKS", "fail-" + index, TestedItem.Status.FAILED));
        }
        recorded.addAll(links(0, 30));
        for (int index = 1; index <= 6; index++) {
            recorded.add(skip(0, "link-skipped-" + index, "exclusion", "EXTERNAL"));
        }

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, published.size());
        assertEquals(5, statusCount(published, TestedItem.Status.FAILED), "every failure is kept");
        // A quarter of the fifteen places left, rounded up.
        assertEquals(4, statusCount(published, TestedItem.Status.SKIPPED));
        assertEquals(11, statusCount(published, TestedItem.Status.PASSED));
    }

    @Test
    void aPlaceHeldForASkipThatDoesNotExistGoesBackToThePasses() {
        // The reservation is a ceiling, never a quota that wastes a place.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(0, 40));
        recorded.add(skip(0, "link-skipped-1", "exclusion", "EXTERNAL"));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, published.size(), "no place is left unfilled");
        assertEquals(1, statusCount(published, TestedItem.Status.SKIPPED));
        assertEquals(19, statusCount(published, TestedItem.Status.PASSED));
    }

    @Test
    void theSampleOfSkipsCoversEveryDistinctReasonBeforeRepeatingOne() {
        // "A representative sample" is the promise this method has always made. Taking the first
        // few in recorded order would publish five external links and lose the one destination
        // that required authentication -- exactly the reason a reader needed.
        List<TestedItem> recorded = new ArrayList<>();
        recorded.addAll(links(0, 30));
        for (int index = 1; index <= 8; index++) {
            recorded.add(skip(0, "external-" + index, "exclusion", "EXTERNAL"));
        }
        recorded.add(skip(0, "protected-1", "linkOutcome", "AUTH_REQUIRED"));
        recorded.add(skip(0, "duplicate-1", "exclusion", "DUPLICATE"));

        List<TestedItem> published = RunSummaryFactory.capDetails(recorded);

        assertEquals(CAP, published.size());
        assertTrue(published.stream().anyMatch(i -> "protected-1".equals(i.id())),
            "the only AUTH_REQUIRED record was dropped: " + ids(published));
        assertTrue(published.stream().anyMatch(i -> "duplicate-1".equals(i.id())),
            "the only DUPLICATE record was dropped: " + ids(published));
        assertEquals(5, statusCount(published, TestedItem.Status.SKIPPED),
            "and the share itself is unchanged");
    }

    @Test
    void theAllowanceItselfIsStillTwenty() {
        assertEquals(20, RunSummaryFactory.MAX_DETAILED_ITEMS_PER_TYPE);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static List<TestedItem> links(int pageNumber, int count) {
        List<TestedItem> items = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            items.add(item(pageNumber, "LINKS", "link-" + index, TestedItem.Status.PASSED));
        }
        return items;
    }

    /** A skipped record carrying the classification the check published for it. */
    private static TestedItem skip(
        int pageNumber, String moduleId, String metaKey, String metaValue
    ) {
        EvidenceScope scope = pageNumber == 0
            ? EvidenceScope.legacy()
            : EvidenceScope.forPage(pageNumber, "https://example.com/page-" + pageNumber, 0, null);
        TestedItem.Builder builder = TestedItem.builder("LINKS", TestedItem.ItemType.LINK)
            .id(scope.itemId(moduleId))
            .name(moduleId)
            .status(TestedItem.Status.SKIPPED)
            .meta(metaKey, metaValue);
        return scope.enrich(builder).build();
    }

    private static long statusCount(List<TestedItem> items, TestedItem.Status status) {
        return items.stream().filter(i -> i.status() == status).count();
    }

    private static List<String> ids(List<TestedItem> items) {
        return items.stream().map(TestedItem::id).toList();
    }

    /** {@code pageNumber} of 0 means no page scope at all, as a single-page run records. */
    private static TestedItem item(
        int pageNumber, String testType, String moduleId, TestedItem.Status status
    ) {
        EvidenceScope scope = pageNumber == 0
            ? EvidenceScope.legacy()
            : EvidenceScope.forPage(pageNumber, "https://example.com/page-" + pageNumber, 0, null);
        TestedItem.Builder builder = TestedItem.builder(testType, TestedItem.ItemType.LINK)
            .id(scope.itemId(moduleId))
            .name(moduleId)
            .status(status);
        return scope.enrich(builder).build();
    }

    private static long countOn(List<TestedItem> items, int pageNumber) {
        String pageId = EvidenceScope.pageId(pageNumber);
        return items.stream()
            .filter(i -> "LINKS".equals(i.testType()))
            .filter(i -> pageId.equals(i.metadata().get(EvidenceScope.META_ID)))
            .count();
    }

    /** The pages present in the detail, in the order they first appear. */
    private static Set<String> pagesIn(List<TestedItem> items) {
        return items.stream()
            .map(i -> i.metadata().get(EvidenceScope.META_ID))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
