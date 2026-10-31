package io.github.markusaugust.streamlord.core

/**
 * API one Streamlord module exposes for another, never for an application: the client's rules
 * the DSL, the adapters and the analysis share, so that each lives once. It may change in any
 * release without notice. Streamlord's own modules opt in through the build; application code
 * that uses it fails to compile unless it opts in itself.
 */
@RequiresOptIn(
    message = "Shared between Streamlord's own modules and not meant for application code; it may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalStreamlordApi
