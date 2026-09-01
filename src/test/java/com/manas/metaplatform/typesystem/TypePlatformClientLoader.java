package com.manas.metaplatform.typesystem;

import com.manas.metaplatform.typesystem.client.GeneratedTypeClient;

/**
 * Loads one of the 40 generated {@code <Type>Client} classes under
 * {@code generated-clients/java/.../generated/client} by name, reflectively, so test code that
 * must drive all 40 clients uniformly (see {@link GeneratedTypeClient}'s own Javadoc) does not
 * need 40 hand-written call sites or an import list that goes stale every time the DSL gains a
 * type. Every generated client shares the same {@code (String baseUrl)} constructor, which is
 * what makes this possible.
 */
final class TypePlatformClientLoader {

    private TypePlatformClientLoader() {
    }

    static GeneratedTypeClient load(String typeName, String baseUrl) throws ReflectiveOperationException {
        Class<?> clientClass = Class.forName("com.manas.metaplatform.generated.client." + typeName + "Client");
        return (GeneratedTypeClient) clientClass.getConstructor(String.class).newInstance(baseUrl);
    }
}
