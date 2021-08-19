package com.github.timojakob.snapper;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.text.MessageFormat;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

public class TickerConsumer implements Runnable {

  private final Logger logger = Logger.getLogger(TickerConsumer.class.getName());
  private int counter = 0;

  private ManagedChannel channel = null;
  private snapper.TickerSimulatorServiceGrpc.TickerSimulatorServiceBlockingStub client;

  private final ConcurrentMap<String, SnapShot> snapMap;

  public TickerConsumer(ConcurrentMap<String, SnapShot> snapMap) {
    this.snapMap = snapMap;
  }

  private void setUpChannel() {
    // define the channel where the ticks come from via gRPC
    channel = ManagedChannelBuilder.forAddress("localhost", 50051)
        .usePlaintext() // we ignore encryption for now
        .build();

    logger.info("ManagedChannel opened for server streaming");

    // Instantiating a client that makes use of the channel
    client = snapper.TickerSimulatorServiceGrpc
        .newBlockingStub(channel);
  }

  private void shutdownChannel() {
    channel.shutdown(); //
    logger.info("Channel shut down");
  }

  private void updateSnapMap(snapper.TickerSimulatorResponse response) {

    if (++counter % 100000 == 0)
      logger.info(MessageFormat.format("{0} ticks consumed", Integer.valueOf(counter)));

    var snapShot = snapMap.get(response.getSymbol());

    if (snapShot == null) {
      // there was never a tick for this symbol. We create a new entry with the new tick
      var newSnapShot =
          new SnapShot(
              response.getTs(),
              response.getPrice(),
              response.getPrice(),
              response.getPrice(),
              response.getVolume(),
              response.getVolume());

      snapMap.put(response.getSymbol(), newSnapShot);
    } else {
      // As there is already a snapshot available, we update it with the new tick.
      var newHigh = (response.getPrice() > snapShot.high() ? response.getPrice() : snapShot.high());
      var newLow = (response.getPrice() < snapShot.low() ? response.getPrice() : snapShot.low());
      var newTotal = response.getVolume() + snapShot.totalVolume();

      var updatedSnapShot =
          new SnapShot(
            response.getTs(),
            response.getPrice(),
            newLow,
            newHigh,
            newTotal,
            response.getVolume());

      snapMap.put(response.getSymbol(), updatedSnapShot);
    }
  }

  @Override
  public void run() {
    // All the openings
    setUpChannel();

    // creating the request to send to the tickserver to retrieve the tick stream
    var request = snapper.TickerSimulatorRequest.newBuilder()
        .setChunkSize(1000000)
        .build();

    try {
    // now let's consume forever until we get interrupted
    while (!Thread.interrupted()) {
      // sending the request and iterating through the stream with the tickerResponses
      client.startTicker(request).forEachRemaining(this::updateSnapMap);
    }
    } catch (StatusRuntimeException e) {
      logger.info("reading ticker stream aborted");
    } finally {
      // As we finished reading from the channel, we close the channel
      shutdownChannel();
    }
  }
}
