package io.cortexj.train.data;

import io.cortexj.error.CortexJException;

import java.util.List;

/**
 * Chat-template presets for Format C (chat SFT). DJL 0.38 exposes no
 * applyChatTemplate, and a Jinja engine is out of scope, so templates
 * are hand-written presets keyed by id, following the DecoderFamily
 * philosophy: no fake compatibility, unknown ids fail typed listing
 * the supported ones. The rendered pair splits at the loss boundary:
 * everything up to and including the assistant generation prompt is
 * context (masked), the assistant content plus its closing turn token
 * is the response (trained).
 */
public final class ChatTemplates {

    /** One conversation turn: system | user | assistant. */
    public static final class Message {

        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String role() {
            return role;
        }

        public String content() {
            return content;
        }
    }

    /** Rendered conversation: context (masked) + response (trained). */
    public static final class Rendered {

        private final String contextText;
        private final String responseText;

        Rendered(String contextText, String responseText) {
            this.contextText = contextText;
            this.responseText = responseText;
        }

        public String contextText() {
            return contextText;
        }

        public String responseText() {
            return responseText;
        }
    }

    /** Supported template ids: {@code qwen} (ChatML), {@code llama3}. */
    public static Rendered render(String templateId, List<Message> messages) {
        if (!"qwen".equals(templateId) && !"llama3".equals(templateId)) {
            CortexJException failure = new CortexJException("CORTEXJ-TRAIN-0113",
                    "unsupported chat template: " + templateId,
                    "Use one of the supported chat templates (qwen, llama3).");
            failure.detail("template", String.valueOf(templateId));
            failure.detail("supported", "[qwen, llama3]");
            throw failure;
        }
        validate(messages);
        return "qwen".equals(templateId)
                ? renderQwen(messages)
                : renderLlama3(messages);
    }

    private static void validate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw invalid("messages must not be empty");
        }
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            boolean last = i == messages.size() - 1;
            boolean assistant = "assistant".equals(message.role());
            if (!"system".equals(message.role()) && !"user".equals(message.role())
                    && !assistant) {
                throw invalid("role must be system, user or assistant: " + message.role());
            }
            if ("system".equals(message.role()) && i != 0) {
                throw invalid("system is only allowed as the first message");
            }
            if (last && !assistant) {
                throw invalid("the last message must be the assistant turn to train on");
            }
            if (assistant && !last) {
                throw invalid("only the final assistant turn is rendered; intermediate"
                        + " assistant turns are not supported in 0.x");
            }
            if (message.content() == null || message.content().isEmpty()) {
                throw invalid("message content must not be empty (role "
                        + message.role() + ")");
            }
        }
    }

    private static CortexJException invalid(String reason) {
        return new CortexJException("CORTEXJ-TRAIN-0112",
                "malformed chat messages: " + reason,
                "Send system (optional, first), user turns and one final non-empty"
                        + " assistant turn.");
    }

    private static Rendered renderQwen(List<Message> messages) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < messages.size() - 1; i++) {
            Message message = messages.get(i);
            context.append("<|im_start|>").append(message.role()).append('\n')
                    .append(message.content()).append("<|im_end|>").append('\n');
        }
        context.append("<|im_start|>assistant\n");
        return new Rendered(context.toString(),
                messages.get(messages.size() - 1).content() + "<|im_end|>");
    }

    private static Rendered renderLlama3(List<Message> messages) {
        StringBuilder context = new StringBuilder("<|begin_of_text|>");
        for (int i = 0; i < messages.size() - 1; i++) {
            Message message = messages.get(i);
            context.append("<|start_header_id|>").append(message.role())
                    .append("<|end_header_id|>\n\n")
                    .append(message.content()).append("<|eot_id|>");
        }
        context.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
        return new Rendered(context.toString(),
                messages.get(messages.size() - 1).content() + "<|eot_id|>");
    }

    private ChatTemplates() {
    }
}
