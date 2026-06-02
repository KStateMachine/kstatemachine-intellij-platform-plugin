package com.github.nsk90.kstatemachineintellijplatformplugin.model

/**
 * One target "outcome" of a transition.
 *
 * - Single target, `isParallel = false`: a regular branch ("on event X go to A",
 *   or one branch of `if (cond) A else B`).
 * - Multiple targets, `isParallel = true`: an atomic parallel split produced by
 *   `targetParallelStates(A, B, …)` — at runtime all listed states become
 *   active simultaneously. The diagram surfaces this with a `<<fork>>` pseudo-
 *   state to keep the semantics distinct from a list of alternative branches.
 *
 * Each [targets] entry is a state-name string in the same shape the rest of
 * the pipeline already consumes (quoted string literal, plain identifier, or
 * an unresolved expression text as a last-resort fallback).
 */
data class TargetGroup(
    val targets: List<String>,
    val isParallel: Boolean,
)
