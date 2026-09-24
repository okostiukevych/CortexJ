package io.cortexj.tck;

import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class RepositoryProviderContract {

    protected abstract ModelRepositoryProvider provider();

    protected abstract ModelReference knownReference();

    protected abstract ModelReference unknownReference();

    public static ResolutionContext onlineContext() {
        return ResolutionContext.create(false, null, null, null);
    }

    public static ResolutionContext offlineContext() {
        return ResolutionContext.create(true, null, null, null);
    }

    @Test
    public void supportsIsPrecise() {
        assertNotNull(provider().id());
        assertTrue(provider().supports(knownReference()),
                "provider must support the reference it is designed for");
        assertFalse(provider().supports(unknownReference()),
                "provider must not claim references outside its scope");
    }

    @Test
    public void resolutionProducesImmutableRevision() {
        ResolvedModel resolved = provider().resolve(knownReference(), onlineContext());
        assertNotNull(resolved.descriptor(), "resolution must produce a descriptor");
        assertNotNull(resolved.repository(), "resolution must record the repository");
        assertTrue(resolved.hasRevision(),
                "resolution must produce an immutable revision where the source provides one");
        assertTrue(!resolved.descriptor().canonicalId().trim().isEmpty(),
                "canonical id must be meaningful");
        assertNotNull(resolved.artifacts());
    }

    @Test
    public void unknownReferenceFailsWithTypedError() {
        assertThrows(RuntimeException.class,
                () -> provider().resolve(unknownReference(), onlineContext()));
    }

    @Test
    public void offlineBehaviorIsExplicit() {
        try {
            provider().resolve(knownReference(), offlineContext());
        } catch (io.cortexj.error.ModelResolutionException expected) {
            assertEquals("CORTEXJ-MODEL-1003", expected.code(),
                    "offline failures must use the dedicated offline error code");
        }
    }
}
