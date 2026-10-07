package io.cortexj.declarative;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;

/**
 * Jackson-backed decoder: extracts the first JSON object/array from the response
 * and maps it to the target type, failing with a typed error when the model
 * output is not valid JSON (spec 48: validation must not be silently skipped).
 */
public final class JsonPromptDecoder<T> implements PromptDecoder<T> {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public T decode(String response, Class<T> targetType) {
        if (response == null) {
            throw new CortexJException(ErrorCode.INFERENCE_FAILED, "model returned no text to decode");
        }
        String json = extractJson(response.trim());
        if (json == null) {
            throw new CortexJException(ErrorCode.INFERENCE_FAILED,
                    "model response contains no JSON object to decode into " + targetType.getName()
                            + " (response: " + preview(response) + ")");
        }
        try {
            return mapper.readValue(json, targetType);
        } catch (Exception malformed) {
            throw new CortexJException(ErrorCode.INFERENCE_FAILED,
                    "model response is not valid JSON for " + targetType.getName() + ": "
                            + malformed.getMessage());
        }
    }

    private static String extractJson(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') {
                continue;
            }
            char open = c;
            char close = c == '{' ? '}' : ']';
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int j = i; j < text.length(); j++) {
                char current = text.charAt(j);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\' && inString) {
                    escaped = true;
                    continue;
                }
                if (current == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (current == open) {
                    depth++;
                } else if (current == close) {
                    depth--;
                    if (depth == 0) {
                        return text.substring(i, j + 1);
                    }
                }
            }
            // audit #118: an unbalanced candidate (prose containing a brace
            // before the JSON - a classic LLM output shape) must not abort the
            // whole scan; keep looking at the next candidate position
        }
        return null;
    }

    private static String preview(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }
}
