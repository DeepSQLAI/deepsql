package com.dbaagent.llm.api;

import org.springframework.ai.chat.model.ChatModel;

/**
 * A chat provider is a <strong>factory</strong>, not a {@link ChatModel}.
 *
 * <p>This is load-bearing. If a provider were the model itself, credentials would be
 * constructor-bound and zero-restart credential rotation — the property that makes this
 * product self-hostable — would be lost. {@code RefreshableChatModel} resolves
 * credentials per call and asks the provider for a delegate.
 */
public interface LlmChatProvider {

    LlmProviderDescriptor descriptor();

    /**
     * Build a model bound to these credentials. Callers cache the result; implementations
     * need not.
     */
    ChatModel delegate(LlmCredentials credentials);

    /** Classify a failure thrown by a delegate this provider produced. */
    LlmErrorCategory classify(Throwable throwable);
}
