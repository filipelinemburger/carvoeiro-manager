package br.com.carvoeiromanager.season;

import java.util.UUID;

/** One entry in the news feed: the item's id and read flag plus its full deserialized payload. */
public record NewsFeedItemResponse(UUID id, boolean read, AdvanceResponse payload) { }
