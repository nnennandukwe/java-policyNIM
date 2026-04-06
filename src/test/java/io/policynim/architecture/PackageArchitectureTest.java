package io.policynim.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.policynim", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageArchitectureTest {

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles =
        slices().matching("io.policynim.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule config_should_not_depend_on_mcp =
        noClasses()
            .that().resideInAPackage("io.policynim.config..")
            .should().dependOnClassesThat().resideInAPackage("io.policynim.mcp..");
}
