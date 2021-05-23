package com.github.timojakob.snapper;

record SnapShot(
    Long ts,
    Integer last,
    Integer low,
    Integer high,
    Integer totalVolume,
    Integer lastVolume) { }
