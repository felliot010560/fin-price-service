package com.aleatory.price.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.aleatory.common.domain.IBIronCondor;
import com.aleatory.common.domain.IronCondor;
import com.aleatory.common.events.TickReceivedEvent;
import com.aleatory.price.events.NewCondorPriceEvent;

@ExtendWith(MockitoExtension.class)
class CondorProviderTest {

    private CondorProvider cut;
    private static final AtomicInteger TICKER_ID = new AtomicInteger(5);
    private static final int BID = 1;
    private static final int ASK = 2;
    private static final double BID_VALUE = -3.2;
    private static final double ASK_VALUE = -1.9;
    private static final double GARBAGE_BID_TOO_LOW = -12.0;
    private static final double GARBAGE_BID_TOO_HIGH = 12.0;
    private static final double GARBAGE_ASK_TOO_HIGH = 12.0;

    private Map<Integer, IronCondor<?>> tickersToCondor;
    private IBIronCondor condor;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @SuppressWarnings("deprecation")
    @BeforeEach
    void setUp() throws Exception {
        cut = new CondorProvider();
        tickersToCondor = new HashMap<>();
        condor = new IBIronCondor();
        tickersToCondor.put(TICKER_ID.get(), condor);

        ReflectionTestUtils.setField(cut, "tickersToCondor", tickersToCondor);
        ReflectionTestUtils.setField(cut, "condorTickerId", TICKER_ID);
        ReflectionTestUtils.setField(cut, "applicationEventPublisher", applicationEventPublisher);
    }

    @Test
    void handleCondorTickHappyPathTest() {
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, BID_VALUE);
        TickReceivedEvent event2 = new TickReceivedEvent(this, TICKER_ID.get(), ASK, ASK_VALUE);

        cut.handleCondorTick(event1);
        cut.handleCondorTick(event2);

        assertEquals(BID_VALUE, condor.getPrice().getBid());
        assertEquals(ASK_VALUE, condor.getPrice().getAsk());
        verify(applicationEventPublisher).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickNotMainTickerIdTest() {
        ReflectionTestUtils.setField(cut, "condorTickerId", new AtomicInteger(6));
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, BID_VALUE);
        TickReceivedEvent event2 = new TickReceivedEvent(this, TICKER_ID.get(), ASK, ASK_VALUE);

        cut.handleCondorTick(event1);
        cut.handleCondorTick(event2);

        assertEquals(BID_VALUE, condor.getPrice().getBid());
        assertEquals(ASK_VALUE, condor.getPrice().getAsk());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickHappyPathWithRoundingTest() {
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, BID_VALUE - .0000000001);
        TickReceivedEvent event2 = new TickReceivedEvent(this, TICKER_ID.get(), ASK, ASK_VALUE + .0000000001);

        cut.handleCondorTick(event1);
        cut.handleCondorTick(event2);

        assertEquals(BID_VALUE, condor.getPrice().getBid());
        assertEquals(ASK_VALUE, condor.getPrice().getAsk());
    }

    @Test
    void handleCondorTickWithGarbageBidTooLowTest() {
        condor.getPrice().setBid(BID_VALUE);
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, GARBAGE_BID_TOO_LOW);

        cut.handleCondorTick(event1);

        assertEquals(BID_VALUE, condor.getPrice().getBid());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickWithGarbageBidTooHighTest() {
        condor.getPrice().setBid(BID_VALUE);
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, GARBAGE_BID_TOO_HIGH);

        cut.handleCondorTick(event1);

        assertEquals(BID_VALUE, condor.getPrice().getBid());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickWithGarbageBidOf0Test() {
        condor.getPrice().setBid(BID_VALUE);
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, 0.0);

        cut.handleCondorTick(event1);

        assertEquals(BID_VALUE, condor.getPrice().getBid());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickWithGarbageAskTooHighTest() {
        condor.getPrice().setAsk(ASK_VALUE);
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), ASK, GARBAGE_ASK_TOO_HIGH);

        cut.handleCondorTick(event1);

        assertEquals(ASK_VALUE, condor.getPrice().getAsk());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickWithGarbageAskOf0Test() {
        condor.getPrice().setAsk(ASK_VALUE);
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), ASK, 0.0);

        cut.handleCondorTick(event1);

        assertEquals(ASK_VALUE, condor.getPrice().getAsk());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickWithBidHigherThanAskTest() {
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, -1.0);
        TickReceivedEvent event2 = new TickReceivedEvent(this, TICKER_ID.get(), ASK, -2.0);

        cut.handleCondorTick(event1);
        cut.handleCondorTick(event2);

        assertEquals(-1.0, condor.getPrice().getBid());
        assertEquals(-2.0, condor.getPrice().getAsk());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickWith0AskDoesntSend() {
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), BID, BID_VALUE);

        cut.handleCondorTick(event1);

        assertEquals(BID_VALUE, condor.getPrice().getBid());
        assertEquals(0.0, condor.getPrice().getAsk());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

    @Test
    void handleCondorTickWith0BidDoesntSend() {
        TickReceivedEvent event1 = new TickReceivedEvent(this, TICKER_ID.get(), ASK, ASK_VALUE);

        cut.handleCondorTick(event1);

        assertEquals(0.0, condor.getPrice().getBid());
        assertEquals(ASK_VALUE, condor.getPrice().getAsk());
        verify(applicationEventPublisher, never()).publishEvent(any(NewCondorPriceEvent.class));
    }

}
