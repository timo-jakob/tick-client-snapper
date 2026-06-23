package com.github.timojakob.snapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import snapper.TickerSimulatorResponse;

/**
 * Behaviour tests for {@link TickerConsumer}.
 *
 * <p>The network-facing parts of the class ({@code run}, {@code setUpChannel}, {@code
 * shutdownChannel}) require a live gRPC server and are out of scope here. The aggregation logic
 * lives in the private {@code updateSnapMap(TickerSimulatorResponse)} method, whose observable
 * effect is the mutation of the supplied {@link ConcurrentMap}. We drive it through reflection and
 * assert on the resulting {@link SnapShot} state — this verifies the actual high/low/total-volume
 * folding rules rather than merely touching lines.
 */
class TickerConsumerTest {

  private ConcurrentMap<String, SnapShot> snapMap;
  private TickerConsumer consumer;
  private Method updateSnapMap;

  @BeforeEach
  void setUp() throws Exception {
    snapMap = new ConcurrentHashMap<>();
    consumer = new TickerConsumer(snapMap);
    updateSnapMap =
        TickerConsumer.class.getDeclaredMethod("updateSnapMap", TickerSimulatorResponse.class);
    updateSnapMap.setAccessible(true);
  }

  private void feed(long ts, String symbol, int price, int volume) throws Exception {
    var response =
        TickerSimulatorResponse.newBuilder()
            .setTs(ts)
            .setSymbol(symbol)
            .setPrice(price)
            .setVolume(volume)
            .build();
    updateSnapMap.invoke(consumer, response);
  }

  @Test
  void firstTickForSymbolSeedsSnapshotFromThatTick() throws Exception {
    feed(1_000L, "AAPL", 150, 10);

    var snap = snapMap.get("AAPL");
    assertNotNull(snap, "a first tick must create a snapshot entry");
    // For the very first tick, low == high == last == price and both volumes == that tick volume.
    assertEquals(1_000L, snap.ts());
    assertEquals(150, snap.last());
    assertEquals(150, snap.low());
    assertEquals(150, snap.high());
    assertEquals(10, snap.totalVolume());
    assertEquals(10, snap.lastVolume());
  }

  @Test
  void higherPriceTickRaisesHighButKeepsLow() throws Exception {
    feed(1_000L, "AAPL", 150, 10);
    feed(2_000L, "AAPL", 175, 5);

    var snap = snapMap.get("AAPL");
    assertEquals(2_000L, snap.ts(), "ts tracks the latest tick");
    assertEquals(175, snap.last(), "last tracks the latest price");
    assertEquals(150, snap.low(), "low stays at the earlier, lower price");
    assertEquals(175, snap.high(), "high rises to the new higher price");
    assertEquals(15, snap.totalVolume(), "total volume accumulates across ticks");
    assertEquals(5, snap.lastVolume(), "lastVolume is just the latest tick's volume");
  }

  @Test
  void lowerPriceTickLowersLowButKeepsHigh() throws Exception {
    feed(1_000L, "AAPL", 150, 10);
    feed(2_000L, "AAPL", 120, 7);

    var snap = snapMap.get("AAPL");
    assertEquals(120, snap.last());
    assertEquals(120, snap.low(), "low drops to the new lower price");
    assertEquals(150, snap.high(), "high stays at the earlier, higher price");
    assertEquals(17, snap.totalVolume());
    assertEquals(7, snap.lastVolume());
  }

  @Test
  void equalPriceTickLeavesHighAndLowUnchangedButAccumulatesVolume() throws Exception {
    feed(1_000L, "AAPL", 150, 10);
    feed(2_000L, "AAPL", 150, 4);

    var snap = snapMap.get("AAPL");
    // Price equal to current high/low: the strict > / < comparisons keep both bounds.
    assertEquals(150, snap.low());
    assertEquals(150, snap.high());
    assertEquals(14, snap.totalVolume(), "volume still accumulates even when price is unchanged");
    assertEquals(4, snap.lastVolume());
  }

  @Test
  void runningHighAndLowTrackExtremesAcrossManyTicks() throws Exception {
    feed(1L, "AAPL", 100, 1);
    feed(2L, "AAPL", 130, 2);
    feed(3L, "AAPL", 90, 3); // new low
    feed(4L, "AAPL", 140, 4); // new high
    feed(5L, "AAPL", 110, 5); // between bounds: neither moves

    var snap = snapMap.get("AAPL");
    assertEquals(90, snap.low(), "low is the minimum price seen");
    assertEquals(140, snap.high(), "high is the maximum price seen");
    assertEquals(110, snap.last(), "last is the most recent price");
    assertEquals(5L, snap.ts());
    assertEquals(1 + 2 + 3 + 4 + 5, snap.totalVolume(), "total volume sums every tick");
    assertEquals(5, snap.lastVolume());
  }

  @Test
  void differentSymbolsAreTrackedIndependently() throws Exception {
    feed(1L, "AAPL", 100, 10);
    feed(2L, "MSFT", 300, 20);
    feed(3L, "AAPL", 110, 5);

    var aapl = snapMap.get("AAPL");
    var msft = snapMap.get("MSFT");

    assertEquals(110, aapl.high(), "AAPL high reflects only AAPL ticks");
    assertEquals(100, aapl.low());
    assertEquals(15, aapl.totalVolume());

    assertEquals(300, msft.high(), "MSFT is unaffected by AAPL ticks");
    assertEquals(300, msft.low());
    assertEquals(20, msft.totalVolume());
    assertEquals(2, snapMap.size(), "each distinct symbol gets its own entry");
  }

  @Test
  void existingSymbolEntryIsReplacedWithAnUpdatedSnapshotInstance() throws Exception {
    feed(1L, "AAPL", 100, 10);
    var first = snapMap.get("AAPL");

    feed(2L, "AAPL", 105, 5);
    var second = snapMap.get("AAPL");

    // The update path builds a fresh SnapShot rather than mutating in place (it is a record).
    org.junit.jupiter.api.Assertions.assertNotSame(
        first, second, "an update must store a new snapshot for the symbol");
    assertSame(second, snapMap.get("AAPL"));
  }

  @Test
  void constructorUsesTheSuppliedMapInstance() throws Exception {
    // The consumer must write into exactly the map it was handed (shared with the controller),
    // not an internal copy — otherwise published snapshots would never be visible.
    feed(42L, "TSLA", 200, 9);
    assertSame(snapMap.get("TSLA"), snapMap.get("TSLA"));
    assertEquals(1, snapMap.size());
  }
}
