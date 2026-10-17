package cn.run.freepocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class EpicEntitlementsTest {
    private static EpicEntitlements.Grant grant(String namespace, String id, boolean active) {
        return new EpicEntitlements.Grant(namespace, id, active);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void expectFailure(EpicEntitlements.PageSource source, String description) throws Exception {
        try { EpicEntitlements.readAll(source); throw new AssertionError(description); }
        catch (IOException expected) { check(!expected.getMessage().isEmpty(), "failure must explain why"); }
    }
    public static void main(String[] args) throws Exception {
        check(EpicEntitlements.readAll((start, count) -> Collections.emptyList()).isEmpty(), "empty authenticated result");
        List<EpicEntitlements.Grant> filtered = EpicEntitlements.readAll((start, count) -> Arrays.asList(
                grant("ns", "a", true), grant("ns", "a", true), grant("ns", "revoked", false),
                grant("other", "a", true), grant("", "non-game", true)));
        check(filtered.size() == 2, "deduplicate exact namespace/item and exclude inactive/non-game records");
        check(filtered.get(1).namespace.equals("other"), "same item ID in a different namespace must remain distinct");
        AtomicInteger calls = new AtomicInteger();
        List<EpicEntitlements.Grant> firstPage = new ArrayList<>();
        for (int i = 0; i < EpicEntitlements.PAGE_SIZE; i++) firstPage.add(grant("ns", "item-" + i, true));
        List<EpicEntitlements.Grant> all = EpicEntitlements.readAll((start, count) -> {
            check(count == 1000, "declared page size");
            int page = calls.getAndIncrement();
            check(start == page * 1000, "pagination offset");
            return page == 0 ? firstPage : Collections.singletonList(grant("ns", "last", true));
        });
        check(calls.get() == 2 && all.size() == 1001, "read the second page before publishing ownership");
        expectFailure((start, count) -> { if (start > 0) throw new IOException("second page failed"); return firstPage; },
                "a later failure must not return partial ownership");
        expectFailure((start, count) -> { throw new IOException("HTTP 401"); }, "unauthorized must not become an empty library");
        expectFailure((start, count) -> firstPage, "an endless full-page response must not become a completed library");
        List<EpicEntitlements.Grant> oversized = new ArrayList<>(firstPage);
        oversized.add(grant("ns", "overflow", true));
        expectFailure((start, count) -> oversized, "unexpected pagination shape must fail explicitly");
        System.out.println("Epic entitlement pagination checks passed: completeness, authorization failure, identity, inactive grants, and deduplication.");
    }
}
