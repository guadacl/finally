package com.finallyapp.kanban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.kanban.AlertRules.Decision;
import com.finallyapp.market.PriceCache;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertEngineTest {

    private PriceCache cache;
    private KanbanRepository repo;
    private KanbanWorkflow workflow;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        cache = new PriceCache();
        repo = mock(KanbanRepository.class);
        workflow = mock(KanbanWorkflow.class);
        org.mockito.Mockito.when(repo.markNotified(any(), any(), any())).thenReturn(true);
        engine = new AlertEngine(cache, repo, workflow);
        engine.register();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void rules() {
        assertThat(AlertRules.isHit(KanbanColumn.SUPPORT, d("185"), d("185.00"))).isTrue();
        assertThat(AlertRules.isHit(KanbanColumn.SUPPORT, d("185"), d("184.99"))).isTrue();
        assertThat(AlertRules.isHit(KanbanColumn.SUPPORT, d("185"), d("185.01"))).isFalse();
        assertThat(AlertRules.isHit(KanbanColumn.RESISTANCE, d("200"), d("200"))).isTrue();
        assertThat(AlertRules.isHit(KanbanColumn.RESISTANCE, d("200"), d("199.99"))).isFalse();
        assertThat(AlertRules.evaluate(KanbanColumn.SUPPORT, d("185"), true, d("184"))).isEqualTo(Decision.FIRE);
        assertThat(AlertRules.evaluate(KanbanColumn.SUPPORT, d("185"), false, d("184"))).isEqualTo(Decision.NONE);
        // a NOTIFIED card does not re-arm when the price moves back
        assertThat(AlertRules.evaluate(KanbanColumn.SUPPORT, d("185"), false, d("186"))).isEqualTo(Decision.NONE);
        assertThat(AlertRules.evaluate(KanbanColumn.SUPPORT, d("185"), true, d("186"))).isEqualTo(Decision.NONE);
        assertThat(AlertRules.status(true)).isEqualTo(AlertRules.Status.ACTIVE);
        assertThat(AlertRules.status(false)).isEqualTo(AlertRules.Status.NOTIFIED);
    }

    @Test
    void supportNotifiesOnlyOnceEvenWhenThePriceCrossesAgain() {
        UUID id = UUID.randomUUID();
        engine.put(id, "user-1", "AAPL", KanbanColumn.SUPPORT, d("185"), true);
        cache.update("AAPL", 190);
        cache.update("AAPL", 184.97);   // cross -> fire, card becomes NOTIFIED
        cache.update("AAPL", 184.50);   // still below -> no second fire
        verify(workflow, timeout(2000).times(1)).targetPriceHit(eq(new KanbanCardRef(id, "AAPL", KanbanColumn.SUPPORT, d("185"), "user-1")), eq(d("184.97")));
        verify(repo, timeout(2000)).markNotified(id, KanbanColumn.SUPPORT, d("185"));

        cache.update("AAPL", 186);      // back above: stays NOTIFIED (no re-arm)
        cache.update("AAPL", 185);      // hit again: no second notification
        cache.update("AAPL", 180);
        verify(workflow, afterDelay().times(1)).targetPriceHit(any(), any());
        verify(repo, times(1)).markNotified(any(), any(), any());
    }

    @Test
    void resistanceFiresWhenPriceRisesToTarget() {
        UUID id = UUID.randomUUID();
        engine.put(id, "user-1", "TSLA", KanbanColumn.RESISTANCE, d("260"), true);
        cache.update("TSLA", 250);
        cache.update("AAPL", 999);      // other tickers ignored
        verify(workflow, after()).targetPriceHit(any(), any());
        cache.update("TSLA", 260.10);
        verify(workflow, timeout(2000)).targetPriceHit(any(), eq(d("260.10")));
    }

    @Test
    void disarmedCardLoadedFromDbDoesNotFireUntilRearmed() {
        UUID id = UUID.randomUUID();
        engine.put(id, "user-1", "AAPL", KanbanColumn.SUPPORT, d("185"), false);
        cache.update("AAPL", 180);
        verify(workflow, after()).targetPriceHit(any(), any());
    }

    @Test
    void editingCardRearmsIt() {
        UUID id = UUID.randomUUID();
        engine.put(id, "user-1", "AAPL", KanbanColumn.SUPPORT, d("185"), true);
        cache.update("AAPL", 180);
        verify(workflow, timeout(2000).times(1)).targetPriceHit(any(), any());
        // user moves the target (service re-arms and replaces in-memory state)
        engine.put(id, "user-1", "AAPL", KanbanColumn.SUPPORT, d("175"), true);
        cache.update("AAPL", 179);
        verify(workflow, afterDelay().times(1)).targetPriceHit(any(), any());
        cache.update("AAPL", 174);
        verify(workflow, timeout(2000).times(2)).targetPriceHit(any(), any());
    }

    @Test
    void hitIsDroppedWhenAnEditCommittedFirst() {
        // the card was edited / moved / deleted between the tick and the dispatcher: the DB compare-and-set fails
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(repo.markNotified(eq(id), any(), any())).thenReturn(false);
        engine.put(id, "user-1", "AAPL", KanbanColumn.SUPPORT, d("185"), true);
        cache.update("AAPL", 180);
        verify(repo, timeout(2000)).markNotified(id, KanbanColumn.SUPPORT, d("185"));
        verify(workflow, after()).targetPriceHit(any(), any());
    }

    @Test
    void hitIsStillNotifiedWhenTheDbIsDown() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(repo.markNotified(eq(id), any(), any())).thenThrow(new RuntimeException("db down"));
        engine.put(id, "user-1", "AAPL", KanbanColumn.SUPPORT, d("185"), true);
        cache.update("AAPL", 180);
        verify(workflow, timeout(2000)).targetPriceHit(any(), eq(d("180.00")));
    }

    @Test
    void removedCardNeverFires() {
        UUID id = UUID.randomUUID();
        engine.put(id, "user-1", "AAPL", KanbanColumn.SUPPORT, d("185"), true);
        engine.remove(id);
        cache.update("AAPL", 100);
        verify(workflow, after()).targetPriceHit(any(), any());
        verify(repo, never()).markNotified(any(), any(), any());
    }

    @Test
    void workflowFailureDoesNotBreakEngine() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RuntimeException("camunda down")).when(workflow).targetPriceHit(any(), any());
        engine.put(first, "user-1", "AAPL", KanbanColumn.RESISTANCE, d("100"), true);
        engine.put(second, "user-1", "MSFT", KanbanColumn.SUPPORT, d("400"), true);
        cache.update("AAPL", 101);
        verify(workflow, timeout(2000)).targetPriceHit(any(), any());
        // the engine keeps evaluating other cards after the failure; the failed card is NOTIFIED and stays quiet
        cache.update("AAPL", 99);
        cache.update("AAPL", 102);
        cache.update("MSFT", 399);
        verify(workflow, timeout(2000).times(2)).targetPriceHit(any(), any());
        verify(workflow, afterDelay().times(1)).targetPriceHit(
                eq(new KanbanCardRef(first, "AAPL", KanbanColumn.RESISTANCE, d("100"), "user-1")), any());
    }

    /** Wait a bit, then verify the call never happened. */
    private static org.mockito.verification.VerificationMode after() {
        return org.mockito.Mockito.after(200).never();
    }

    private static org.mockito.verification.VerificationAfterDelay afterDelay() {
        return org.mockito.Mockito.after(200);
    }
}
