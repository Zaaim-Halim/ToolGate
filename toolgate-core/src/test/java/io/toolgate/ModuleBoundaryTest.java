package io.toolgate;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the public surface: anything exported becomes API that users can depend on, and anything
 * required becomes a dependency forced on every application that embeds the core.
 */
class ModuleBoundaryTest {

    private static final Set<String> PUBLIC_PACKAGES = Set.of("io.toolgate.api", "io.toolgate.api.policy", "io.toolgate.spi",
            "io.toolgate.telemetry");
    /** Only the telemetry package uses these, so an application that does not use it never needs them. */
    private static final Set<String> OPTIONAL_MODULES = Set.of("micrometer.core", "io.opentelemetry.api",
            "io.opentelemetry.context");

    private final ModuleDescriptor descriptor = ModuleBoundaryTest.class.getModule().getDescriptor();

    @Test
    void testsRunInsideTheNamedModule() {
        assertThat(ModuleBoundaryTest.class.getModule().isNamed())
                .as("tests must run on the module path, or the boundary checks below see nothing")
                .isTrue();
        assertThat(descriptor.name()).isEqualTo("io.toolgate.core");
    }

    @Test
    void exportsNothingButApiSpiAndTelemetry() {
        assertThat(descriptor.exports())
                .extracting(Exports::source)
                .allSatisfy(exported -> assertThat(PUBLIC_PACKAGES).contains(exported));
    }

    @Test
    void neverExportsToSelectedModulesOnly() {
        assertThat(descriptor.exports()).noneMatch(Exports::isQualified);
    }

    @Test
    void requiresNothingButTheJavaBaseModuleAndTheOptionalTelemetryLibraries() {
        assertThat(descriptor.requires()).filteredOn(required -> !required.modifiers().contains(Requires.Modifier.STATIC))
                .extracting(Requires::name).containsExactly("java.base");
        assertThat(descriptor.requires()).filteredOn(required -> required.modifiers().contains(Requires.Modifier.STATIC))
                .extracting(Requires::name).containsExactlyInAnyOrderElementsOf(OPTIONAL_MODULES);
    }
}
