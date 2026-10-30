package com.saathi.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * §3's module boundaries and the two rules the shared kernel exists to make structural:
 * a module owns its tables and may not reach into another module, and the wall clock is read in
 * exactly one place.
 *
 * <p>This class is the teeth behind the sentence repeated in every {@code package-info.java}.
 */
class ModuleBoundaryTest {

    /** §3's module list. Anything else under {@code com.saathi} is an undeclared module. */
    private static final Set<String> MODULES = Set.of(
        "identity", "tenancy", "merchant", "catalog", "cart", "quote", "order", "delivery",
        "payment", "ledger", "settlement", "support", "notification", "admin", "stock", "jobs");

    private static final String SHARED = "shared";

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("com.saathi");

    @Test
    void thereAreNoDependencyCyclesBetweenModules() {
        // beFreeOfCycles, not notDependOnEachOther. {@code order -> delivery} is a legal edge under
        // §3, and a rule that bans legal edges gets switched off rather than widened. What §3
        // forbids is a cycle — two modules that each own the other's tables — and the leaf rule
        // below is what keeps the shared kernel out of one.
        ArchRule rule = SlicesRuleDefinition.slices()
            .matching("com.saathi.(*)..")
            .should().beFreeOfCycles();
        rule.allowEmptyShould(true)
            .because("System Design §3: a module owns its tables; a cycle means two modules own each other")
            .check(CLASSES);
    }

    @Test
    void theSharedKernelDependsOnNoModule() {
        // The kernel is depended upon by everyone and may depend on no one, or the whole graph
        // becomes one cycle and the rule above proves nothing.
        ArchRule fields = noFields().that().areDeclaredInClassesThat().resideInAPackage(kernel())
            .should().haveRawType(typeInAnyModule());
        fields.allowEmptyShould(true).because("shared/ is the leaf of the dependency graph (§3)")
            .check(CLASSES);

        ArchRule dependencies = noClasses().that().resideInAPackage(kernel())
            .should().dependOnClassesThat(typeInAnyModule());
        dependencies.allowEmptyShould(true).because("shared/ is the leaf of the dependency graph (§3)")
            .check(CLASSES);
    }

    @Test
    void thereIsNoUndeclaredModule() {
        Set<String> found = CLASSES.stream()
            .map(JavaClass::getName)
            .filter(name -> name.startsWith("com.saathi."))
            .map(name -> name.substring("com.saathi.".length()))
            .filter(rest -> rest.contains("."))
            .map(rest -> rest.substring(0, rest.indexOf('.')))
            .filter(segment -> !segment.equals(SHARED) && !segment.equals("architecture"))
            .collect(Collectors.toCollection(TreeSet::new));

        Set<String> undeclared = new TreeSet<>(found);
        undeclared.removeAll(MODULES);
        org.junit.jupiter.api.Assertions.assertEquals(Set.of(), undeclared,
            "§3's module list is closed; adding one is a design change, not a package rename");
    }

    /**
     * Expiry, {@code business_day}, quote staleness and the audit timestamp all come from the
     * injected {@link Clock}, which is what makes §6.5's "no timers in the order lifecycle"
     * testable: a fixed clock is the only way to assert that a quote is stale.
     */
    @Test
    void nobodyReadsTheWallClock() {
        noClasses().that(insideThePlatformButNotTheClockFactory())
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(Clock.class, "systemUTC")
            .orShould().callMethod(Clock.class, "systemDefaultZone")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .allowEmptyShould(true)
            .because("§3: an injected Clock, never a static call")
            .check(CLASSES);
    }

    private static String kernel() {
        return "com.saathi." + SHARED + "..";
    }

    private static DescribedPredicate<JavaClass> typeInAnyModule() {
        String[] packages = MODULES.stream().map(m -> "com.saathi." + m + "..").toArray(String[]::new);
        return JavaClass.Predicates.resideInAnyPackage(packages);
    }

    private static DescribedPredicate<JavaClass> insideThePlatformButNotTheClockFactory() {
        return JavaClass.Predicates.resideInAPackage("com.saathi..")
            .and(new DescribedPredicate<JavaClass>("is not the clock factory") {
                @Override
                public boolean test(JavaClass javaClass) {
                    // ClockConfig is the one class allowed to reach for the system clock, because
                    // handing everyone else a Clock is its entire job.
                    return !javaClass.getName().equals("com.saathi.shared.config.ClockConfig");
                }
            });
    }
}
