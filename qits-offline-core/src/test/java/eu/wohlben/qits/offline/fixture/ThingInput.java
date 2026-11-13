package eu.wohlben.qits.offline.fixture;

/** The fixture's request body. {@code id} is ignored by updates. */
public record ThingInput(String id, String name) {}
