/**
 * Ready-made audit sinks and decision observers for Micrometer and OpenTelemetry. Nothing here is
 * used unless the application adds it to the gate's builder.
 *
 * <p>Core declares Micrometer ({@code micrometer-core}) and the OpenTelemetry API
 * ({@code opentelemetry-api}) as optional dependencies: an application that uses a class from this
 * package brings its own copy of the library that class names. No other package of core touches them.
 */
package io.toolgate.telemetry;
