package com.github.timojakob.snapper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SnapperController {

  private final Logger logger = Logger.getLogger(SnapperController.class.getName());
  private final ConcurrentHashMap<String, SnapShot> snapMap = new ConcurrentHashMap<>();
  private Thread tickConsumerThread = null;

  @GetMapping("/subscribe")
  public void subscribe() {
    logger.info("GET request subscribe called");

    // create a new consumer thread for incoming ticks
    var tickConsumer = new TickerConsumer(snapMap);
    tickConsumerThread = new Thread(tickConsumer, "Consumer-Thread");
    tickConsumerThread.start();
  }

  @GetMapping("/unsubscribe")
  public void unsubscribe() {
    logger.info("GET request to unsubscribe called");
    if (tickConsumerThread != null) {
      tickConsumerThread.interrupt();
    }
  }

  @GetMapping("/snapshot")
  public ResponseEntity<SnapShot> snapshot(@RequestParam(name = "symbol") String symbol) {
    logger.info("GET request snapshot called");

    var snapShot = snapMap.get(symbol);
    if (snapShot == null)
      return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    else 
      return ResponseEntity.ok().body(snapShot);
  }
}
