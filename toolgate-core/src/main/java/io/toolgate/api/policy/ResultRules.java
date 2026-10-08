package io.toolgate.api.policy;

/**
 * Factories for result checks that compare a field directly, meant to be imported statically.
 * For fields holding record IDs, use {@link ArgumentRules#sameTenant} and
 * {@link ArgumentRules#ownedByPrincipal}, which ask the application's resolver.
 *
 * <pre>{@code
 * .result("searchInvoices", "items[*].tenantId", matchesTenant())
 * .result("findCustomer", "customer.id", ownedByPrincipal("customer"))
 * }</pre>
 */
public final class ResultRules {

    private static final ResultCheck.MatchesTenant MATCHES_TENANT = new ResultCheck.MatchesTenant();
    private static final ResultCheck.MatchesPrincipal MATCHES_PRINCIPAL = new ResultCheck.MatchesPrincipal();

    private ResultRules() {}

    /**
     * Every value must be the principal's tenant ID; a principal without a tenant matches nothing.
     *
     * @return the check
     */
    public static ResultCheck matchesTenant() {
        return MATCHES_TENANT;
    }

    /**
     * Every value must be the principal's ID.
     *
     * @return the check
     */
    public static ResultCheck matchesPrincipal() {
        return MATCHES_PRINCIPAL;
    }
}
