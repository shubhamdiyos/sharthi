package com.saathi.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * PRD §10 and System Design §3: currency is integer paise. A {@code double} in the money path
 * is not a style preference — 0.1 + 0.2 is 0.30000000000000004, and the platform's whole claim
 * is that an order's totals reconcile to the paisa (§9.7 I-1).
 *
 * <p>The scope is the money path, not the whole program: GPS coordinates and a tolerance
 * percentage are legitimately floating-point one day, and a rule that bans them everywhere gets
 * switched off rather than widened (§14's ban on {@code @SuppressWarnings} is the other half of
 * that argument).
 */
class MoneyArchitectureTest {

    private static final String[] MONEY_PATH = {
        "com.saathi.shared.money..",
        "com.saathi.cart..",
        "com.saathi.quote..",
        "com.saathi.order..",
        "com.saathi.ledger..",
        "com.saathi.settlement..",
        "com.saathi.payment..",
        "com.saathi.delivery..",
    };

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("com.saathi");

    private static final DescribedPredicate<JavaClass> FLOATING_POINT =
        new DescribedPredicate<JavaClass>("double or float") {
            @Override
            public boolean test(JavaClass type) {
                return type.getName().equals("double") || type.getName().equals("float")
                    || type.getName().equals("java.lang.Double") || type.getName().equals("java.lang.Float");
            }
        };

    @Test
    void noFieldInTheMoneyPathIsAFloatingPointNumber() {
        moneyRule(noFields().that().areDeclaredInClassesThat().resideInAnyPackage(MONEY_PATH)
            .should().haveRawType(FLOATING_POINT));
    }

    @Test
    void noMethodInTheMoneyPathReturnsAFloatingPointNumber() {
        moneyRule(noMethods().that().areDeclaredInClassesThat().resideInAnyPackage(MONEY_PATH)
            .should().haveRawReturnType(FLOATING_POINT));
    }

    @Test
    void noMethodInTheMoneyPathTakesAFloatingPointParameter() {
        moneyRule(noMethods().that().areDeclaredInClassesThat().resideInAnyPackage(MONEY_PATH)
            .should().haveRawParameterTypes(anyFloatingPoint()));
    }

    /**
     * A parameter list qualifies if any position is floating-point — including a widened
     * {@code double...} varargs tail, which arrives as an array of the banned type.
     */
    private static DescribedPredicate<java.util.List<JavaClass>> anyFloatingPoint() {
        return new DescribedPredicate<java.util.List<JavaClass>>("a parameter of type double or float") {
            @Override
            public boolean test(java.util.List<JavaClass> parameters) {
                return parameters.stream().anyMatch(MoneyArchitectureTest::isFloatingPoint);
            }
        };
    }

    private static boolean isFloatingPoint(JavaClass type) {
        JavaClass probe = type.isArray() ? type.getBaseComponentType() : type;
        return FLOATING_POINT.test(probe);
    }

    private static void moneyRule(ArchRule rule) {
        // allowEmptyShould: seven of these eight packages are empty until their module is built,
        // and an empty selection must not read as a pass on code that has not been written yet.
        rule.allowEmptyShould(true)
            .because("currency is integer paise (PRD §10, System Design §3) — see Money and Rate")
            .check(CLASSES);
    }

    /**
     * The ban is only real if the rule can fire. This imports a deliberately non-compliant class
     * from source at test time — a rule that cannot fail is documentation, not a control.
     */
    @Test
    void theRuleActuallyFiresOnFloatingPointMoney() {
        JavaClasses offender = new ClassFileImporter()
            .importClasses(ViolatesTheRule.class);
        ArchRule rule = noFields()
            .that().areDeclaredInClassesThat().resideInAnyPackage("com.saathi.architecture..")
            .should().haveRawType(double.class);
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> rule.check(offender));
    }

    @SuppressWarnings("unused")
    private static class ViolatesTheRule {
        double price;
    }
}
