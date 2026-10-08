/**
 * Building a policy: which tools each agent may call, under which rules, and in which mode.
 *
 * <p>Policies are written in Java as the source of truth, validated when built, and immutable
 * afterwards. Anything a policy does not allow is denied.
 */
package io.toolgate.api.policy;
