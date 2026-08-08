package com.dbaagent.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class or method as requiring LLM configuration to run.
 *
 * <p>Tests annotated with this will be skipped (not failed) when LLM credentials
 * are not configured via {@code DEEPSQL_CHAT_*} or {@code DEEPSQL_EMBEDDING_*}
 * environment variables, or via the database configuration.
 *
 * <p>This uses the same resolution logic as the production {@code LlmConfigResolver},
 * ensuring tests skip only when the production code would also fail.
 *
 * <p>Usage:
 * <pre>{@code
 * @RequiresLlm
 * class ChatIntegrationTest {
 *     // All tests in this class require LLM
 * }
 *
 * class MixedTest {
 *     @Test
 *     void testWithoutLlm() { }
 *
 *     @Test
 *     @RequiresLlm
 *     void testWithLlm() { }
 * }
 *
 * @RequiresLlm(chat = true, embedding = false)
 * class ChatOnlyTest {
 *     // Only requires chat LLM, not embedding
 * }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(LlmAvailabilityCondition.class)
public @interface RequiresLlm {

    /**
     * Whether the test requires chat LLM to be configured.
     * Defaults to {@code true}.
     */
    boolean chat() default true;

    /**
     * Whether the test requires embedding LLM to be configured.
     * Defaults to {@code false} since many chat tests don't need embeddings.
     */
    boolean embedding() default false;
}
