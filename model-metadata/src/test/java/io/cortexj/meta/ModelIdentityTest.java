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
        assertEquals(ModelIdentity.of("a", "1"), ModelIdentity.of("a", "1"));
        assertEquals(ModelIdentity.of("a", "1").hashCode(), ModelIdentity.of("a", "1").hashCode());
    }
}
