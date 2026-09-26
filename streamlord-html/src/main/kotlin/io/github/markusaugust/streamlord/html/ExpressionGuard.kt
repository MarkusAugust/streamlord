package io.github.markusaugust.streamlord.html

/*
 * The guard moved to streamlord-core in 0.2.0, so that HTML written as a string can be checked
 * without the DSL. These aliases keep 0.1.1 code compiling.
 */

/** Moved to `io.github.markusaugust.streamlord.core.domain.ExpressionGuard`. */
@Deprecated(
    "Moved to streamlord-core",
    ReplaceWith("ExpressionGuard", "io.github.markusaugust.streamlord.core.domain.ExpressionGuard"),
)
public typealias ExpressionGuard = io.github.markusaugust.streamlord.core.domain.ExpressionGuard

/** Moved to `io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException`. */
@Deprecated(
    "Moved to streamlord-core",
    ReplaceWith("InterpolatedExpressionException", "io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException"),
)
public typealias InterpolatedExpressionException = io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
