/**
 * The agent loop: tool-calling over the retrieval surface.
 *
 * <p>Starts as a single agent with a small tool set. A well-scoped single loop is
 * both cheaper and easier to debug than a planner/critic ensemble; additional stages
 * are added if the eval harness shows they improve results.

 */
@org.springframework.modulith.ApplicationModule(displayName = "Agent")
package dev.rovernotes.agent;
