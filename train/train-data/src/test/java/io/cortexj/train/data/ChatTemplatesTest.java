package io.cortexj.train.data;

import io.cortexj.error.CortexJException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTemplatesTest {

    private List<ChatTemplates.Message> convo(String system, String user, String assistant) {
        List<ChatTemplates.Message> messages = new ArrayList<>();
        if (system != null) {
            messages.add(new ChatTemplates.Message("system", system));
        }
        messages.add(new ChatTemplates.Message("user", user));
        messages.add(new ChatTemplates.Message("assistant", assistant));
        return messages;
    }

    @Test
    void qwenRendersChatMlWithTheLossBoundaryBeforeTheResponse() {
        ChatTemplates.Rendered rendered = ChatTemplates.render("qwen",
                convo("be terse", "hi", "hello"));
        assertEquals("<|im_start|>system\nbe terse<|im_end|>\n"
                        + "<|im_start|>user\nhi<|im_end|>\n"
                        + "<|im_start|>assistant\n",
                rendered.contextText());
        assertEquals("hello<|im_end|>", rendered.responseText());
    }

    @Test
    void qwenWithoutSystemStartsAtUser() {
        ChatTemplates.Rendered rendered = ChatTemplates.render("qwen",
                convo(null, "hi", "hello"));
        assertEquals("<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n",
                rendered.contextText());
    }

    @Test
    void llama3RendersTheLlamaThreeScheme() {
        ChatTemplates.Rendered rendered = ChatTemplates.render("llama3",
                convo("be terse", "hi", "hello"));
        assertEquals("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n"
                        + "be terse<|eot_id|>"
                        + "<|start_header_id|>user<|end_header_id|>\n\n"
                        + "hi<|eot_id|>"
                        + "<|start_header_id|>assistant<|end_header_id|>\n\n",
                rendered.contextText());
        assertEquals("hello<|eot_id|>", rendered.responseText());
    }

    @Test
    void unknownTemplateFailsTypedListingSupported() {
        CortexJException failure = assertThrows(CortexJException.class,
                () -> ChatTemplates.render("jinja-mega", convo(null, "hi", "hello")));
        assertEquals("CORTEXJ-TRAIN-0113", failure.code());
        assertTrue(failure.getMessage().contains("jinja-mega"));
        assertTrue(failure.toString().contains("qwen"));
        assertTrue(failure.toString().contains("llama3"));
    }

    @Test
    void structuralViolationsFailTyped() {
        // assistant not last
        List<ChatTemplates.Message> wrongOrder = Arrays.asList(
                new ChatTemplates.Message("assistant", "hello"),
                new ChatTemplates.Message("user", "hi"));
        CortexJException order = assertThrows(CortexJException.class,
                () -> ChatTemplates.render("qwen", wrongOrder));
        assertEquals("CORTEXJ-TRAIN-0112", order.code());

        // empty assistant content - nothing to train on
        CortexJException empty = assertThrows(CortexJException.class,
                () -> ChatTemplates.render("qwen", convo(null, "hi", "")));
        assertEquals("CORTEXJ-TRAIN-0112", empty.code());

        // unknown role
        CortexJException role = assertThrows(CortexJException.class,
                () -> ChatTemplates.render("qwen",
                        Arrays.asList(new ChatTemplates.Message("tool", "x"),
                                new ChatTemplates.Message("assistant", "y"))));
        assertEquals("CORTEXJ-TRAIN-0112", role.code());

        // system not first
        CortexJException system = assertThrows(CortexJException.class,
                () -> ChatTemplates.render("qwen",
                        Arrays.asList(new ChatTemplates.Message("user", "hi"),
                                new ChatTemplates.Message("system", "x"),
                                new ChatTemplates.Message("assistant", "y"))));
        assertEquals("CORTEXJ-TRAIN-0112", system.code());
    }
}
