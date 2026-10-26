package io.cortexj.declarative;

import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.model.ModelHandle;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Declarative Java API: turns an annotated interface into model calls.
 *
 * <pre>{@code
 * public interface InvoiceExtractor {
 *     @ModelPrompt("Extract the total amount from this invoice as JSON {\"total\": ...}: {document}")
 *     InvoiceTotal extract(String document);
 * }
 *
 * InvoiceExtractor extractor = ModelInterfaces.of(handle, InvoiceExtractor.class,
 *         new JsonPromptDecoder<InvoiceTotal>());
 * InvoiceTotal total = extractor.extract(invoiceText);
 * }</pre>
 *
 * <p>This is a separate artifact on purpose: the runtime platform must not
 * turn into an application framework. Extraction is prompt + validation,
 * not guaranteed structured decoding.</p>
 */
public final class ModelInterfaces {

    private static final Pattern PARAMETER = Pattern.compile("\\{(\\w+)\\}");

    private ModelInterfaces() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T of(ModelHandle handle, Class<T> contract, PromptDecoder<?> decoder) {
        if (handle == null) {
            throw new IllegalArgumentException("model handle must not be null");
        }
        if (contract == null || !contract.isInterface()) {
            throw new IllegalArgumentException("contract must be an interface type");
        }
        return (T) Proxy.newProxyInstance(contract.getClassLoader(),
                new Class<?>[]{contract},
                new PromptInvocationHandler(handle, decoder));
    }

    private static final class PromptInvocationHandler implements InvocationHandler {

        private final ModelHandle handle;
        private final PromptDecoder<?> decoder;

        PromptInvocationHandler(ModelHandle handle, PromptDecoder<?> decoder) {
            this.handle = handle;
            this.decoder = decoder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                String methodName = method.getName();
                if ("equals".equals(methodName)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                if ("hashCode".equals(methodName)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(methodName)) {
                    return "ModelInterfacesProxy[" + handle.descriptor().canonicalId() + "]";
                }
                return method.invoke(this, args);
            }
            if (method.isDefault()) {
                throw new UnsupportedOperationException("default interface methods are not supported by ModelInterfaces proxy: " + method.getName());
            }
            ModelPrompt prompt = method.getAnnotation(ModelPrompt.class);
            if (prompt == null) {
                throw new IllegalStateException("method " + method.getName()
                        + " is not annotated with @ModelPrompt and has no declarative mapping");
            }
            String rendered = render(prompt.value(), namedArgs(method, args));
            GenerationResponse response = handle.generate(GenerationRequest.prompt(rendered));
            String text = response.text();
            if (method.getReturnType() == String.class) {
                return text;
            }
            if (decoder == null) {
                throw new IllegalStateException("method " + method.getName()
                        + " returns " + method.getReturnType().getName()
                        + " but no PromptDecoder was provided");
            }
            return decode(text, method.getReturnType());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object decode(String text, Class<?> targetType) {
            PromptDecoder raw = decoder == null
                    ? new JsonPromptDecoder() : decoder;
            return raw.decode(text, targetType);
        }

        private static java.util.Map<String, String> namedArgs(Method method, Object[] args) {
            java.util.Map<String, String> named =
                    new java.util.LinkedHashMap<String, String>();
            java.lang.reflect.Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                Object value = args == null ? null : args[i];
                String strValue = value == null ? "" : String.valueOf(value);
                named.put("arg" + i, strValue);
                String paramName = parameters[i].getName();
                if (paramName != null && !paramName.isEmpty()) {
                    named.put(paramName, strValue);
                }
            }
            return named;
        }

        private static String render(String template, java.util.Map<String, String> named) {
            Matcher matcher = PARAMETER.matcher(template);
            StringBuffer rendered = new StringBuffer();
            String singleArgFallback = named.containsKey("arg0") && !named.containsKey("arg1")
                    ? named.get("arg0")
                    : null;
            while (matcher.find()) {
                String key = matcher.group(1);
                String value;
                if (named.containsKey(key)) {
                    value = named.get(key);
                } else if (singleArgFallback != null) {
                    value = singleArgFallback;
                } else {
                    value = "{" + key + "}";
                }
                matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(rendered);
            return rendered.toString();
        }
    }
}
