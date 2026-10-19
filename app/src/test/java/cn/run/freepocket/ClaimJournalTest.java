package cn.run.freepocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ClaimJournalTest {
    private static final long NOW = 1800000000000L;
    private JSONObject item(String key, String category, String status) throws Exception {
        return new JSONObject().put("key", key).put("category", category).put("title", key)
                .put("status", status).put("start", NOW - 1000).put("end", "2099-01-01T00:00:00Z");
    }
    private ClaimJournal ready() throws Exception {
        ClaimJournal journal = new ClaimJournal("{}");
        journal.start("CN", List.of("pc"), null);
        journal.bind("pc", "account-a", "示例账号");
        journal.plan("pc", new JSONArray().put(item("pc-a", "pc", "unowned")), NOW);
        return journal;
    }
    @Test public void allCategoriesRetainIndependentProductKeysAndOwnedAndFutureAreSkipped() throws Exception {
        ClaimJournal journal = new ClaimJournal("{}");journal.start("CN", List.of("pc", "android", "fab"), null);
        JSONArray catalogue = new JSONArray().put(item("pc-a", "pc", "owned")).put(item("mobile-a", "android", "unowned"))
                .put(item("fab-a", "fab", "unowned")).put(item("future", "pc", "unowned").put("start", NOW + 10000));
        for (String category : journal.categories()) { journal.bind(category, category.equals("fab")?"fab-account":"epic-account", "示例");journal.plan(category,catalogue,NOW); }
        assertEquals("owned",journal.entry("pc-a").getString("status"));
        assertEquals("skipped",journal.entry("future").getString("status"));
        assertEquals(1,journal.work("android").size());assertEquals(1,journal.work("fab").size());
    }
    @Test public void interruptedUnsubmittedWorkResumesAfterFreshInventory() throws Exception {
        ClaimJournal journal=ready();journal.working("pc-a");
        ClaimJournal restored=new ClaimJournal(journal.serialize());
        assertEquals("paused",restored.current().getString("state"));
        restored.resume("CN");restored.bind("pc","account-a","示例账号");
        restored.plan("pc",new JSONArray().put(item("pc-a","pc","unowned")),NOW);
        assertEquals("pending",restored.entry("pc-a").getString("status"));
    }
    @Test public void crashAfterCheckpointNeverReplaysAnUnconfirmedOrder() throws Exception {
        ClaimJournal journal=ready();journal.reserve("pc-a","product");
        ClaimJournal restored=new ClaimJournal(journal.serialize());restored.resume("CN");
        restored.bind("pc","account-a","示例账号");restored.plan("pc",new JSONArray().put(item("pc-a","pc","unowned")),NOW);
        assertEquals("uncertain",restored.entry("pc-a").getString("status"));
        assertThrows(JSONException.class,()->restored.reserve("pc-a","product"));
        restored.result("pc-a","unowned","暂未入库");assertEquals("uncertain",restored.entry("pc-a").getString("status"));
        assertThrows(JSONException.class,()->restored.prechecked("pc-a"));
    }
    @Test public void productAndSubmitEachPermitOnlyOnceAndRequireRecognizedStages() throws Exception {
        ClaimJournal journal=ready();journal.reserve("pc-a","product");journal.reserve("pc-a","submit");
        assertThrows(JSONException.class,()->journal.reserve("pc-a","submit"));
        assertThrows(JSONException.class,()->journal.reserve("pc-a","other"));
        journal.result("pc-a","owned","官方确认");assertEquals("claimed",journal.entry("pc-a").getString("status"));
    }
    @Test public void newBatchAndTrimmedHistoryCannotBypassOldOrderReservations() throws Exception {
        ClaimJournal journal=ready();journal.reserve("pc-a","product");journal.pause("中断");
        for(int i=0;i<22;i++) journal.start("CN",List.of("pc"),null);
        journal.bind("pc","account-a","示例账号");journal.plan("pc",new JSONArray().put(item("pc-a","pc","unowned")),NOW);
        assertEquals(20,journal.publicRuns().length());assertEquals("uncertain",journal.entry("pc-a").getString("status"));
        assertThrows(JSONException.class,()->journal.reserve("pc-a","product"));
    }
    @Test public void reservationsAreAccountScopedAndResumeRejectsAnotherIdentityOrRegion() throws Exception {
        ClaimJournal journal=ready();journal.reserve("pc-a","product");journal.pause("中断");
        assertThrows(JSONException.class,()->journal.bind("pc","account-b","另一个账号"));
        assertThrows(JSONException.class,()->journal.bind("pc","","登录已失效"));
        assertThrows(JSONException.class,()->journal.resume("HK"));
        journal.start("CN",List.of("pc"),null);journal.bind("pc","account-b","另一个账号");
        journal.plan("pc",new JSONArray().put(item("pc-a","pc","unowned")),NOW);
        assertEquals("pending",journal.entry("pc-a").getString("status"));
    }
    @Test public void failedUnreadAndCachedOwnershipCannotBecomeAClaimTask() throws Exception {
        for(String status:List.of("unknown","error")) {
            ClaimJournal journal=ready();journal.plan("pc",new JSONArray().put(item("pc-a","pc",status)),NOW);
            assertEquals(1,journal.work("pc").size());assertTrue(journal.entry("pc-a").getBoolean("pageCheck"));
            assertThrows(JSONException.class,()->journal.reserve("pc-a","product"));
            journal.prechecked("pc-a");journal.reserve("pc-a","product");
        }
        ClaimJournal journal=ready();journal.plan("pc",new JSONArray().put(item("pc-a","pc","unowned").put("cached",true)),NOW);
        assertTrue(journal.entry("pc-a").getBoolean("pageCheck"));
        ClaimJournal reserved=ready();reserved.reserve("pc-a","product");
        reserved.plan("pc",new JSONArray().put(item("pc-a","pc","owned").put("cached",true)),NOW);
        assertEquals("uncertain",reserved.entry("pc-a").getString("status"));
    }
    @Test public void successfulAndOwnedEntriesAreNotRequeuedWhileFailedOnesCanRetry() throws Exception {
        ClaimJournal journal=ready();journal.result("pc-a","unowned","网络故障前未提交");
        assertEquals("failed",journal.entry("pc-a").getString("status"));
        journal.plan("pc",new JSONArray().put(item("pc-a","pc","unowned")),NOW);assertEquals(1,journal.work("pc").size());
        journal.result("pc-a","owned","官方确认");
        journal.plan("pc",new JSONArray().put(item("pc-a","pc","unowned")),NOW);assertTrue(journal.work("pc").isEmpty());
    }
    @Test public void retryKeepsOriginalTargetsAndDoesNotAddNewlyPublishedProducts() throws Exception {
        ClaimJournal journal=ready();journal.plan("pc",new JSONArray().put(item("pc-a","pc","unowned")).put(item("pc-b","pc","unowned")),NOW);
        assertNull(journal.entry("pc-b"));
    }
    @Test public void publicHistoryContainsNoAccountIdentifiersOrPrivateReservations() throws Exception {
        ClaimJournal journal=ready();journal.reserve("pc-a","product");String json=journal.publicRuns().toString();
        assertFalse(json.contains("account-a"));assertFalse(json.contains("reservations"));assertFalse(json.contains("targets"));
        assertTrue(json.contains("示例账号"));
    }
    @Test public void currentTimeAllowsStartedPreviewsAndRejectsEndedPromotions() throws Exception {
        assertTrue(MainActivity.claimableNow(item("pc-a","pc","unowned").put("availability","upcoming"),NOW));
        assertFalse(MainActivity.claimableNow(item("pc-a","pc","unowned").put("end", "2001-01-01T00:00:00Z"),NOW));
    }
    @Test public void invalidPersistedVersionIsNotSilentlyReplacedWithAnEmptyJournal() {
        assertThrows(JSONException.class,()->new ClaimJournal("{\"schema\":2}"));
        assertThrows(JSONException.class,()->new ClaimJournal("broken"));
    }
}
