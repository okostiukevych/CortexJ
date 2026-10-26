package io.cortexj.meta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelIdentityTest {

    @Test
    void ofKeepsIdAndRevision() {
        ModelIdentity identity = ModelIdentity.of("hf:Qwen/Qwen3-8B", "7f62");
        assertEquals("hf:Qwen/Qwen3-8B", identity.id());
        assertEquals("7f62", identity.revision());
    }

    @Test
    void revisionMayBeNullWhileUnresolved() {
        assertNull(ModelIdentity.of("file:/models/a.gguf", null).revision());
    }

    @Test
    void nullIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ModelIdentity.of(null, "r"));
    }

    @Test
    void valueSemantics() {
        ModelIdentity a1 = ModelIdentity.of("a", "1");
        ModelIdentity a1Copy = ModelIdentity.of("a", "1");
        ModelIdentity a2 = ModelIdentity.of("a", "2");
        ModelIdentity b1 = ModelIdentity.of("b", "1");
        ModelIdentity aNull = ModelIdentity.of("a", null);
        ModelIdentity aNullCopy = ModelIdentity.of("a", null);

        assertEquals(a1, a1);
        assertEquals(a1, a1Copy);
        assertEquals(a1.hashCode(), a1Copy.hashCode());
        assertEquals(aNull, aNullCopy);
        assertEquals(aNull.hashCode(), aNullCopy.hashCode());
        org.junit.jupiter.api.Assertions.assertNotEquals(a1, a2);
        org.junit.jupiter.api.Assertions.assertNotEquals(a1, b1);
        org.junit.jupiter.api.Assertions.assertNotEquals(a1, aNull);
        org.junit.jupiter.api.Assertions.assertNotEquals(aNull, a1);
        org.junit.jupiter.api.Assertions.assertNotEquals(a1, "not-an-identity");
        org.junit.jupiter.api.Assertions.assertNotEquals(a1, null);
    }

    @Test
    void toStringFormatsResolvedAndUnresolved() {
        assertEquals("hf:Qwen/Qwen3-8B@7f62", ModelIdentity.of("hf:Qwen/Qwen3-8B", "7f62").toString());
        assertEquals("file:/models/a.gguf", ModelIdentity.of("file:/models/a.gguf", null).toString());
    }
}
