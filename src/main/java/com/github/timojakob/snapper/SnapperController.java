package com.github.timojakob.snapper;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.logging.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SnapperController {

  private final Logger logger = Logger.getLogger(SnapperController.class.getName());

  @GetMapping("/subscribe")
  public void subscribe() {

    logger.info("GET request subscribe called");

    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 50051)
        .usePlaintext()
        .build();

    try {
      logger.info("ManagedChannel opened for server streaming");

      var client = snapper.TickerSimulatorServiceGrpc
          .newBlockingStub(channel);
      var request = snapper.TickerSimulatorRequest.newBuilder()
          .setActive(true)
          .build();

      client.startTicker(request)
          .forEachRemaining(tickerResponse -> {

            logger.info(MessageFormat.format("TICK : Timestamp: {0}, Symbol: {1}, Price: {2}, Volumne: {3} ",
                Instant.ofEpochSecond(tickerResponse.getTs()),
                tickerResponse.getSymbol(),
                tickerResponse.getPrice(),
                tickerResponse.getVolume()));
          });

      logger.info("END of stream");
    } finally {
      channel.shutdown();
    }
  }

  @GetMapping("/snapshot")
  public ResponseEntity<SnapShot> snapshot() {
    logger.info("GET request snapshot called");
    var snapShot = new SnapShot(1,2,3,4,5);
    logger.info(snapShot.toString());

    return ResponseEntity.ok().body(snapShot);
  }
}
