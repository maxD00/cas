package org.apereo.cas.web.security;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.core.monitor.ActuatorEndpointProperties;
import org.apereo.cas.configuration.model.core.monitor.JaasSecurityActuatorEndpointsMonitorProperties;
import org.apereo.cas.configuration.model.core.monitor.LdapSecurityActuatorEndpointsMonitorProperties;
import org.apereo.cas.util.LdapUtils;
import org.apereo.cas.util.spring.beans.BeanSupplier;
import org.apereo.cas.web.CasWebSecurityConstants;
import org.apereo.cas.web.ProtocolEndpointWebSecurityConfigurer;
import org.apereo.cas.web.security.authentication.EndpointLdapAuthenticationProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jooq.lambda.Unchecked;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.expression.SecurityExpressionHandler;
import org.springframework.security.authentication.jaas.JaasAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.security.web.FilterInvocation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * This is {@link CasWebSecurityConfigurerAdapter}.
 *
 * @author Misagh Moayyed
 * @since 6.0.0
 */
@Slf4j
@Order(CasWebSecurityConstants.SECURITY_CONFIGURATION_ORDER)
@RequiredArgsConstructor
public class CasWebSecurityConfigurerAdapter extends WebSecurityConfigurerAdapter implements DisposableBean {
    /**
     * Endpoint url used for admin-level form-login of endpoints.
     */
    public static final String ENDPOINT_URL_ADMIN_FORM_LOGIN = "/adminlogin";

    private final CasConfigurationProperties casProperties;

    private final SecurityProperties securityProperties;

    private final SecurityExpressionHandler<FilterInvocation> casWebSecurityExpressionHandler;

    private final ObjectProvider<PathMappedEndpoints> pathMappedEndpoints;

    private final List<ProtocolEndpointWebSecurityConfigurer> protocolEndpointWebSecurityConfigurers;

    private EndpointLdapAuthenticationProvider endpointLdapAuthenticationProvider;

    @Override
    public void destroy() {
        if (endpointLdapAuthenticationProvider != null) {
            endpointLdapAuthenticationProvider.destroy();
        }
    }

    @Override
    protected void configure(final AuthenticationManagerBuilder auth) throws Exception {
        val jaas = casProperties.getMonitor().getEndpoints().getJaas();
        if (jaas.getLoginConfig() != null) {
            configureJaasAuthenticationProvider(auth, jaas);
        } else {
            LOGGER.trace("No JAAS login config is defined to enable JAAS authentication");
        }

        val ldap = casProperties.getMonitor().getEndpoints().getLdap();
        if (StringUtils.isNotBlank(ldap.getLdapUrl()) && StringUtils.isNotBlank(ldap.getSearchFilter())) {
            configureLdapAuthenticationProvider(auth, ldap);
        } else {
            LOGGER.trace("No LDAP url or search filter is defined to enable LDAP authentication");
        }

        if (!auth.isConfigured()) {
            super.configure(auth);
        }
    }

    /**
     * Disable Spring Security configuration for protocol endpoints
     * allowing CAS' own security configuration to handle protection
     * of endpoints where necessary.
     *
     * @param http http security
     */
    @Override
    protected void configure(final HttpSecurity http) throws Exception {
        http
            .csrf()
            .disable()
            .headers()
            .disable()
            .logout()
            .disable()
            .requiresChannel()
            .requestMatchers(r -> r.getHeader("X-Forwarded-Proto") != null)
            .requiresSecure();

        val requests = http.authorizeRequests().expressionHandler(casWebSecurityExpressionHandler);
        val patterns = protocolEndpointWebSecurityConfigurers.stream()
            .filter(BeanSupplier::isNotProxy)
            .map(ProtocolEndpointWebSecurityConfigurer::getIgnoredEndpoints)
            .flatMap(List<String>::stream)
            .map(endpoint -> StringUtils.prependIfMissing(endpoint, "/").concat("/**"))
            .collect(Collectors.toList());

        patterns.add("/webjars/**");
        patterns.add("/js/**");
        patterns.add("/css/**");
        patterns.add("/images/**");
        patterns.add("/static/**");
        patterns.add("/error");
        patterns.add("/favicon.ico");

        LOGGER.debug("Configuring protocol endpoints [{}] to exclude/ignore from web security", patterns);
        requests.antMatchers(patterns.toArray(String[]::new)).permitAll();

        val endpoints = casProperties.getMonitor().getEndpoints().getEndpoint();
        endpoints.forEach(Unchecked.biConsumer((k, v) -> {
            val endpoint = EndpointRequest.to(k);
            v.getAccess().forEach(Unchecked.consumer(access -> configureEndpointAccess(http, requests, access, v, endpoint)));
        }));
        configureEndpointAccessToDenyUndefined(http, requests);
        configureEndpointAccessForStaticResources(requests);
        protocolEndpointWebSecurityConfigurers.forEach(cfg -> cfg.configure(http));
    }

    /**
     * Configure endpoint access to deny undefined.
     *
     * @param http     the http
     * @param requests the requests
     */
    protected void configureEndpointAccessToDenyUndefined(
        final HttpSecurity http,
        final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests) {
        val endpoints = casProperties.getMonitor().getEndpoints().getEndpoint().keySet();
        val endpointDefaults = casProperties.getMonitor().getEndpoints().getDefaultEndpointProperties();
        pathMappedEndpoints.getObject().forEach(endpoint -> {
            val rootPath = endpoint.getRootPath();
            if (endpoints.contains(rootPath)) {
                LOGGER.trace("Endpoint security is defined for endpoint [{}]", rootPath);
            } else {
                val defaultAccessRules = endpointDefaults.getAccess();
                LOGGER.trace("Endpoint security is NOT defined for endpoint [{}]. Using default security rules [{}]", rootPath, endpointDefaults);
                val endpointRequest = EndpointRequest.to(rootPath).excludingLinks();
                defaultAccessRules.forEach(Unchecked.consumer(access ->
                    configureEndpointAccess(http, requests, access, endpointDefaults, endpointRequest)));
            }
        });
    }

    /**
     * Configure ldap authentication provider.
     *
     * @param auth the auth
     * @param ldap the ldap
     */
    protected void configureLdapAuthenticationProvider(final AuthenticationManagerBuilder auth,
                                                       final LdapSecurityActuatorEndpointsMonitorProperties ldap) {
        if (isLdapAuthorizationActive()) {
            val connectionFactory = LdapUtils.newLdaptiveConnectionFactory(ldap);
            val authenticator = LdapUtils.newLdaptiveAuthenticator(ldap);
            endpointLdapAuthenticationProvider = new EndpointLdapAuthenticationProvider(ldap, securityProperties, connectionFactory, authenticator);
            auth.authenticationProvider(endpointLdapAuthenticationProvider);
        } else {
            LOGGER.trace("LDAP authorization is undefined, given no LDAP url, base-dn, search filter or role/group filter is configured");
        }
    }

    /**
     * Configure jaas authentication provider.
     *
     * @param auth the auth
     * @param jaas the jaas
     * @throws Exception the exception
     */
    protected void configureJaasAuthenticationProvider(final AuthenticationManagerBuilder auth,
                                                       final JaasSecurityActuatorEndpointsMonitorProperties jaas) throws Exception {
        val p = new JaasAuthenticationProvider();
        p.setLoginConfig(jaas.getLoginConfig());
        p.setLoginContextName(jaas.getLoginContextName());
        p.setRefreshConfigurationOnStartup(jaas.isRefreshConfigurationOnStartup());
        p.afterPropertiesSet();
        auth.authenticationProvider(p);
    }

    /**
     * Configure endpoint access for static resources.
     *
     * @param requests the requests
     */
    protected void configureEndpointAccessForStaticResources(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests) {
        requests
            .requestMatchers(PathRequest.toStaticResources().atCommonLocations())
            .permitAll();
        requests
            .antMatchers("/resources/**")
            .permitAll()
            .antMatchers("/static/**")
            .permitAll();
    }

    /**
     * Configure endpoint access by form login.
     *
     * @param http the http
     * @throws Exception the exception
     */
    protected void configureEndpointAccessByFormLogin(final HttpSecurity http) throws Exception {
        if (casProperties.getMonitor().getEndpoints().isFormLoginEnabled()) {
            http.formLogin().loginPage(ENDPOINT_URL_ADMIN_FORM_LOGIN).permitAll();
        } else {
            http.formLogin().disable();
        }
    }

    /**
     * Configure endpoint access.
     *
     * @param httpSecurity the httpSecurity
     * @param requests     the requests
     * @param access       the access
     * @param properties   the properties
     * @param endpoint     the endpoint
     * @throws Exception the exception
     */
    protected void configureEndpointAccess(final HttpSecurity httpSecurity,
                                           final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                           final ActuatorEndpointProperties.EndpointAccessLevel access,
                                           final ActuatorEndpointProperties properties,
                                           final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        switch (access) {
            case AUTHORITY:
                configureEndpointAccessByAuthority(requests, properties, endpoint);
                break;
            case ROLE:
                configureEndpointAccessByRole(requests, properties, endpoint);
                break;
            case AUTHENTICATED:
                configureEndpointAccessAuthenticated(requests, endpoint);
                break;
            case IP_ADDRESS:
                configureEndpointAccessByIpAddress(requests, properties, endpoint);
                break;
            case PERMIT:
                configureEndpointAccessPermitAll(requests, endpoint);
                break;
            case ANONYMOUS:
                configureEndpointAccessAnonymously(requests, endpoint);
                break;
            case DENY:
            default:
                configureEndpointAccessToDenyAll(requests, endpoint);
                break;
        }
    }

    private void configureEndpointAccessPermitAll(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                                  final EndpointRequest.EndpointRequestMatcher endpoint) {
        requests.requestMatchers(endpoint).permitAll();
    }

    private void configureEndpointAccessToDenyAll(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                                  final EndpointRequest.EndpointRequestMatcher endpoint) {
        requests.requestMatchers(endpoint).denyAll();
    }

    private void configureEndpointAccessAnonymously(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                                    final EndpointRequest.EndpointRequestMatcher endpoint) {

        requests.requestMatchers(endpoint).anonymous();
    }

    private void configureEndpointAccessByIpAddress(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                                    final ActuatorEndpointProperties properties,
                                                    final EndpointRequest.EndpointRequestMatcher endpoint) {
        val addresses = properties.getRequiredIpAddresses()
            .stream()
            .map(address -> "hasIpAddress('" + address + "')")
            .collect(Collectors.joining(" or "));

        requests
            .requestMatchers(endpoint)
            .access(addresses);
    }

    private void configureEndpointAccessAuthenticated(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                                      final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        val result = requests.requestMatchers(endpoint)
            .authenticated()
            .and()
            .httpBasic()
            .and();
        configureEndpointAccessByFormLogin(result);
    }

    private void configureEndpointAccessByRole(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                               final ActuatorEndpointProperties properties,
                                               final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        val result = requests.requestMatchers(endpoint)
            .hasAnyRole(properties.getRequiredRoles().toArray(ArrayUtils.EMPTY_STRING_ARRAY))
            .and()
            .httpBasic()
            .and();
        configureEndpointAccessByFormLogin(result);
    }

    private void configureEndpointAccessByAuthority(final ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry requests,
                                                    final ActuatorEndpointProperties properties,
                                                    final EndpointRequest.EndpointRequestMatcher endpoint) throws Exception {
        val result = requests.requestMatchers(endpoint)
            .hasAnyAuthority(properties.getRequiredAuthorities().toArray(ArrayUtils.EMPTY_STRING_ARRAY))
            .and()
            .httpBasic()
            .and();
        configureEndpointAccessByFormLogin(result);
    }

    private boolean isLdapAuthorizationActive() {
        val ldap = casProperties.getMonitor().getEndpoints().getLdap();
        return StringUtils.isNotBlank(ldap.getBaseDn())
               && StringUtils.isNotBlank(ldap.getLdapUrl())
               && StringUtils.isNotBlank(ldap.getSearchFilter())
               && (StringUtils.isNotBlank(ldap.getLdapAuthz().getRoleAttribute())
                   || StringUtils.isNotBlank(ldap.getLdapAuthz().getGroupAttribute()));
    }
}
