package com.manas.metaplatform.typesystem.client;

import java.util.List;
import java.util.Map;

/**
 * The uniform surface every generated {@code <Type>Client} implements, alongside its own
 * typed convenience methods (see {@code generated-clients/java/.../AssetClient.java} for an
 * example). The typed methods are what make the client "typed" (a real
 * {@code create(Asset body)} that only accepts an {@code Asset}); this interface exists only
 * so a test harness that must drive all 40 generated clients uniformly (the differential
 * benchmark) can do so without 40 separate call sites, exactly the way a generated OpenAPI
 * client suite still needs a common driver in its own test code.
 */
public interface GeneratedTypeClient {

    String typeName();

    Map<String, Object> create(Map<String, Object> fields);

    Map<String, Object> getById(long id);

    List<Map<String, Object>> list(Map<String, Object> equalityFilters);
}
