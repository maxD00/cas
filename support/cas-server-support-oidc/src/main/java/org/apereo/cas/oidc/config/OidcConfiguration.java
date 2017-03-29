package org.apereo.cas.oidc.config;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.AuthenticationServiceSelectionPlan;
import org.apereo.cas.authentication.AuthenticationServiceSelectionStrategy;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.principal.DefaultPrincipalFactory;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.support.oidc.OidcProperties;
import org.apereo.cas.oidc.OidcConstants;
import org.apereo.cas.oidc.claims.BaseOidcScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.claims.OidcCustomScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.discovery.OidcServerDiscoverySettings;
import org.apereo.cas.oidc.discovery.OidcServerDiscoverySettingsFactory;
import org.apereo.cas.oidc.dynareg.OidcClientRegistrationRequest;
import org.apereo.cas.oidc.dynareg.OidcClientRegistrationRequestSerializer;
import org.apereo.cas.oidc.jwks.OidcDefaultJsonWebKeystoreCacheLoader;
import org.apereo.cas.oidc.jwks.OidcJsonWebKeystoreGeneratorService;
import org.apereo.cas.oidc.jwks.OidcServiceJsonWebKeystoreCacheLoader;
import org.apereo.cas.oidc.profile.OidcProfileScopeToAttributesFilter;
import org.apereo.cas.oidc.token.OidcIdTokenGeneratorService;
import org.apereo.cas.oidc.token.OidcIdTokenSigningAndEncryptionService;
import org.apereo.cas.oidc.util.OidcAuthorizationRequestSupport;
import org.apereo.cas.oidc.web.OidcAccessTokenResponseGenerator;
import org.apereo.cas.oidc.web.OidcCallbackAuthorizeViewResolver;
import org.apereo.cas.oidc.web.OidcCasClientRedirectActionBuilder;
import org.apereo.cas.oidc.web.OidcConsentApprovalViewResolver;
import org.apereo.cas.oidc.web.OidcHandlerInterceptorAdapter;
import org.apereo.cas.oidc.web.OidcSecurityInterceptor;
import org.apereo.cas.oidc.web.controllers.OidcAccessTokenEndpointController;
import org.apereo.cas.oidc.web.controllers.OidcAuthorizeEndpointController;
import org.apereo.cas.oidc.web.controllers.OidcDynamicClientRegistrationEndpointController;
import org.apereo.cas.oidc.web.controllers.OidcJwksEndpointController;
import org.apereo.cas.oidc.web.controllers.OidcProfileEndpointController;
import org.apereo.cas.oidc.web.controllers.OidcWellKnownEndpointController;
import org.apereo.cas.oidc.web.flow.OidcAuthenticationContextWebflowEventEventResolver;
import org.apereo.cas.oidc.web.flow.OidcRegisteredServiceUIAction;
import org.apereo.cas.oidc.web.flow.OidcWebflowConfigurer;
import org.apereo.cas.services.MultifactorAuthenticationProviderSelector;
import org.apereo.cas.services.OidcRegisteredService;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.oauth.authenticator.Authenticators;
import org.apereo.cas.support.oauth.authenticator.OAuth20CasAuthenticationBuilder;
import org.apereo.cas.support.oauth.profile.OAuth20ProfileScopeToAttributesFilter;
import org.apereo.cas.support.oauth.validator.OAuth20Validator;
import org.apereo.cas.support.oauth.web.response.OAuth20CasClientRedirectActionBuilder;
import org.apereo.cas.support.oauth.web.response.accesstoken.AccessTokenResponseGenerator;
import org.apereo.cas.support.oauth.web.views.ConsentApprovalViewResolver;
import org.apereo.cas.support.oauth.web.views.OAuth20CallbackAuthorizeViewResolver;
import org.apereo.cas.ticket.accesstoken.AccessTokenFactory;
import org.apereo.cas.ticket.code.OAuthCodeFactory;
import org.apereo.cas.ticket.refreshtoken.RefreshTokenFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.ticket.registry.TicketRegistrySupport;
import org.apereo.cas.util.gen.DefaultRandomStringGenerator;
import org.apereo.cas.util.serialization.StringSerializer;
import org.apereo.cas.web.flow.CasWebflowConfigurer;
import org.apereo.cas.web.flow.authentication.FirstMultifactorAuthenticationProviderSelector;
import org.apereo.cas.web.flow.resolver.CasDelegatingWebflowEventResolver;
import org.apereo.cas.web.flow.resolver.CasWebflowEventResolver;
import org.apereo.cas.web.support.CookieRetrievingCookieGenerator;
import org.jose4j.jwk.RsaJsonWebKey;
import org.pac4j.cas.client.CasClient;
import org.pac4j.core.config.Config;
import org.pac4j.springframework.web.SecurityInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
import org.springframework.web.util.CookieGenerator;
import org.springframework.webflow.definition.registry.FlowDefinitionRegistry;
import org.springframework.webflow.engine.builder.support.FlowBuilderServices;
import org.springframework.webflow.execution.Action;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This is {@link OidcConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Configuration("oidcConfiguration")
@EnableConfigurationProperties(CasConfigurationProperties.class)
public class OidcConfiguration extends WebMvcConfigurerAdapter {

    @Autowired
    @Qualifier("webApplicationServiceFactory")
    private ServiceFactory<WebApplicationService> webApplicationServiceFactory;

    @Autowired
    @Qualifier("requiresAuthenticationAccessTokenInterceptor")
    private SecurityInterceptor requiresAuthenticationAccessTokenInterceptor;

    @Autowired(required = false)
    @Qualifier("multifactorAuthenticationProviderSelector")
    private MultifactorAuthenticationProviderSelector multifactorAuthenticationProviderSelector =
            new FirstMultifactorAuthenticationProviderSelector();

    @Autowired
    @Qualifier("oauthCasAuthenticationBuilder")
    private OAuth20CasAuthenticationBuilder authenticationBuilder;
    
    @Autowired
    @Qualifier("warnCookieGenerator")
    private CookieGenerator warnCookieGenerator;

    @Autowired
    @Qualifier("loginFlowRegistry")
    private FlowDefinitionRegistry loginFlowDefinitionRegistry;

    @Autowired
    @Qualifier("logoutFlowRegistry")
    private FlowDefinitionRegistry logoutFlowDefinitionRegistry;

    @Autowired
    private FlowBuilderServices flowBuilderServices;

    @Autowired
    @Qualifier("initialAuthenticationAttemptWebflowEventResolver")
    private CasDelegatingWebflowEventResolver initialAuthenticationAttemptWebflowEventResolver;

    @Autowired
    @Qualifier("centralAuthenticationService")
    private CentralAuthenticationService centralAuthenticationService;

    @Autowired
    @Qualifier("defaultAuthenticationSystemSupport")
    private AuthenticationSystemSupport authenticationSystemSupport;

    @Autowired
    @Qualifier("oauth20AuthenticationServiceSelectionStrategy")
    private AuthenticationServiceSelectionStrategy oauth20AuthenticationServiceSelectionStrategy;

    @Autowired
    private CasConfigurationProperties casProperties;

    @Autowired
    @Qualifier("oauthSecConfig")
    private Config oauthSecConfig;

    @Autowired
    @Qualifier("ticketGrantingTicketCookieGenerator")
    private CookieRetrievingCookieGenerator ticketGrantingTicketCookieGenerator;

    @Autowired
    @Qualifier("defaultTicketRegistrySupport")
    private TicketRegistrySupport ticketRegistrySupport;

    @Autowired
    @Qualifier("defaultAccessTokenFactory")
    private AccessTokenFactory defaultAccessTokenFactory;

    @Autowired
    @Qualifier("defaultRefreshTokenFactory")
    private RefreshTokenFactory defaultRefreshTokenFactory;

    @Autowired
    @Qualifier("servicesManager")
    private ServicesManager servicesManager;

    @Autowired
    @Qualifier("ticketRegistry")
    private TicketRegistry ticketRegistry;

    @Autowired
    @Qualifier("oAuthValidator")
    private OAuth20Validator oAuth20Validator;

    @Autowired
    @Qualifier("defaultOAuthCodeFactory")
    private OAuthCodeFactory defaultOAuthCodeFactory;

    @Autowired
    @Qualifier("authenticationServiceSelectionPlan")
    private AuthenticationServiceSelectionPlan authenticationRequestServiceSelectionStrategies;
    
    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(oauthInterceptor()).addPathPatterns('/' + OidcConstants.BASE_OIDC_URL.concat("/").concat("*"));
    }

    @Bean
    public ConsentApprovalViewResolver consentApprovalViewResolver() {
        return new OidcConsentApprovalViewResolver(casProperties);
    }

    @Bean
    public OAuth20CallbackAuthorizeViewResolver callbackAuthorizeViewResolver() {
        return new OidcCallbackAuthorizeViewResolver(oidcAuthorizationRequestSupport());
    }

    @Bean
    public OAuth20CasClientRedirectActionBuilder oauthCasClientRedirectActionBuilder() {
        return new OidcCasClientRedirectActionBuilder(oidcAuthorizationRequestSupport());
    }

    @Bean
    public HandlerInterceptorAdapter requiresAuthenticationDynamicRegistrationInterceptor() {
        final String clients = Stream.of(
                Authenticators.CAS_OAUTH_CLIENT_BASIC_AUTHN,
                Authenticators.CAS_OAUTH_CLIENT_DIRECT_FORM,
                Authenticators.CAS_OAUTH_CLIENT_USER_FORM).collect(Collectors.joining(","));
        return new SecurityInterceptor(oauthSecConfig, clients);
    }

    @Bean
    public HandlerInterceptorAdapter requiresAuthenticationAuthorizeInterceptor() {
        final String name = oauthSecConfig.getClients().findClient(CasClient.class).getName();
        return new OidcSecurityInterceptor(oauthSecConfig, name, oidcAuthorizationRequestSupport());
    }

    @Bean
    public OAuth20CasClientRedirectActionBuilder oidcCasClientRedirectActionBuilder() {
        return new OidcCasClientRedirectActionBuilder(oidcAuthorizationRequestSupport());
    }

    @RefreshScope
    @Bean
    public OidcIdTokenGeneratorService oidcIdTokenGenerator() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        return new OidcIdTokenGeneratorService(oidc.getIssuer(), oidc.getSkew(),
                oidcTokenSigningAndEncryptionService());
    }

    @Bean
    @RefreshScope
    public AccessTokenResponseGenerator oidcAccessTokenResponseGenerator() {
        return new OidcAccessTokenResponseGenerator(oidcIdTokenGenerator());
    }

    @Bean
    public OidcAuthorizationRequestSupport oidcAuthorizationRequestSupport() {
        return new OidcAuthorizationRequestSupport(ticketGrantingTicketCookieGenerator, ticketRegistrySupport);
    }

    @ConditionalOnMissingBean(name = "oidcPrincipalFactory")
    @Bean
    public PrincipalFactory oidcPrincipalFactory() {
        return new DefaultPrincipalFactory();
    }

    @Bean
    public OAuth20ProfileScopeToAttributesFilter profileScopeToAttributesFilter() {
        return new OidcProfileScopeToAttributesFilter(oidcPrincipalFactory(), servicesManager,
                userDefinedScopeBasedAttributeReleasePolicies());
    }

    @RefreshScope
    @Bean
    public OidcAccessTokenEndpointController oidcAccessTokenController() {
        return new OidcAccessTokenEndpointController(
                servicesManager, ticketRegistry, oAuth20Validator, defaultAccessTokenFactory,
                oidcPrincipalFactory(), webApplicationServiceFactory, defaultRefreshTokenFactory,
                oidcAccessTokenResponseGenerator(), profileScopeToAttributesFilter(), casProperties,
                ticketGrantingTicketCookieGenerator, authenticationBuilder);
    }

    @Bean
    public StringSerializer<OidcClientRegistrationRequest> clientRegistrationRequestSerializer() {
        return new OidcClientRegistrationRequestSerializer();
    }

    @RefreshScope
    @Bean
    public OidcDynamicClientRegistrationEndpointController oidcDynamicClientRegistrationEndpointController() {
        return new OidcDynamicClientRegistrationEndpointController(
                servicesManager, ticketRegistry, oAuth20Validator, defaultAccessTokenFactory,
                oidcPrincipalFactory(), webApplicationServiceFactory, clientRegistrationRequestSerializer(),
                new DefaultRandomStringGenerator(),
                new DefaultRandomStringGenerator(),
                profileScopeToAttributesFilter(),
                casProperties, ticketGrantingTicketCookieGenerator);
    }

    @RefreshScope
    @Bean
    public OidcJwksEndpointController oidcJwksController() {
        return new OidcJwksEndpointController(servicesManager, ticketRegistry, oAuth20Validator,
                defaultAccessTokenFactory,
                oidcPrincipalFactory(), webApplicationServiceFactory,
                profileScopeToAttributesFilter(), casProperties, ticketGrantingTicketCookieGenerator);
    }

    @Autowired
    @RefreshScope
    @Bean
    public OidcWellKnownEndpointController oidcWellKnownController(@Qualifier("oidcServerDiscoverySettingsFactory")
                                                                   final OidcServerDiscoverySettings discoverySettings) {
        return new OidcWellKnownEndpointController(servicesManager, ticketRegistry,
                oAuth20Validator, defaultAccessTokenFactory,
                oidcPrincipalFactory(), webApplicationServiceFactory,
                discoverySettings, profileScopeToAttributesFilter(),
                casProperties, ticketGrantingTicketCookieGenerator);
    }

    @RefreshScope
    @Bean
    public OidcProfileEndpointController oidcProfileController() {
        return new OidcProfileEndpointController(servicesManager, ticketRegistry, oAuth20Validator,
                defaultAccessTokenFactory,
                oidcPrincipalFactory(), webApplicationServiceFactory,
                profileScopeToAttributesFilter(),
                casProperties, ticketGrantingTicketCookieGenerator);
    }

    @RefreshScope
    @Bean
    public OidcAuthorizeEndpointController oidcAuthorizeController() {
        return new OidcAuthorizeEndpointController(servicesManager,
                ticketRegistry, oAuth20Validator, defaultAccessTokenFactory,
                oidcPrincipalFactory(), webApplicationServiceFactory, defaultOAuthCodeFactory,
                consentApprovalViewResolver(), oidcIdTokenGenerator(),
                profileScopeToAttributesFilter(), casProperties, ticketGrantingTicketCookieGenerator,
                authenticationBuilder);
    }

    @RefreshScope
    @Bean
    public CasWebflowEventResolver oidcAuthenticationContextWebflowEventResolver() {
        return new OidcAuthenticationContextWebflowEventEventResolver(authenticationSystemSupport,
                centralAuthenticationService, servicesManager,
                ticketRegistrySupport, warnCookieGenerator, authenticationRequestServiceSelectionStrategies,
                multifactorAuthenticationProviderSelector);
    }

    @Bean
    public CasWebflowConfigurer oidcWebflowConfigurer() {
        final OidcWebflowConfigurer cfg = new OidcWebflowConfigurer(flowBuilderServices,
                loginFlowDefinitionRegistry, oidcRegisteredServiceUIAction());
        cfg.setLogoutFlowDefinitionRegistry(logoutFlowDefinitionRegistry);
        return cfg;
    }

    @ConditionalOnMissingBean(name = "oidcRegisteredServiceUIAction")
    @Bean
    public Action oidcRegisteredServiceUIAction() {
        return new OidcRegisteredServiceUIAction(this.servicesManager, oauth20AuthenticationServiceSelectionStrategy);
    }

    @Bean
    public OidcIdTokenSigningAndEncryptionService oidcTokenSigningAndEncryptionService() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        return new OidcIdTokenSigningAndEncryptionService(oidcDefaultJsonWebKeystoreCache(),
                oidcServiceJsonWebKeystoreCache(),
                oidc.getIssuer());
    }

    @Bean
    public LoadingCache<OidcRegisteredService, Optional<RsaJsonWebKey>> oidcServiceJsonWebKeystoreCache() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        final LoadingCache<OidcRegisteredService, Optional<RsaJsonWebKey>> cache =
                CacheBuilder.newBuilder().maximumSize(1)
                        .expireAfterWrite(oidc.getJwksCacheInMinutes(), TimeUnit.MINUTES)
                        .build(oidcServiceJsonWebKeystoreCacheLoader());
        return cache;
    }

    @Bean
    public LoadingCache<String, Optional<RsaJsonWebKey>> oidcDefaultJsonWebKeystoreCache() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        final LoadingCache<String, Optional<RsaJsonWebKey>> cache =
                CacheBuilder.newBuilder().maximumSize(1)
                        .expireAfterWrite(oidc.getJwksCacheInMinutes(), TimeUnit.MINUTES)
                        .build(oidcDefaultJsonWebKeystoreCacheLoader());
        return cache;
    }

    @Bean
    public OidcDefaultJsonWebKeystoreCacheLoader oidcDefaultJsonWebKeystoreCacheLoader() {
        return new OidcDefaultJsonWebKeystoreCacheLoader(casProperties.getAuthn().getOidc().getJwksFile());
    }

    @Bean
    public OidcServiceJsonWebKeystoreCacheLoader oidcServiceJsonWebKeystoreCacheLoader() {
        return new OidcServiceJsonWebKeystoreCacheLoader();
    }

    @Bean
    public OidcServerDiscoverySettingsFactory oidcServerDiscoverySettingsFactory() {
        return new OidcServerDiscoverySettingsFactory(casProperties);
    }

    @Bean
    public OidcJsonWebKeystoreGeneratorService oidcJsonWebKeystoreGeneratorService() {
        return new OidcJsonWebKeystoreGeneratorService(casProperties.getAuthn().getOidc());
    }

    @Bean
    public HandlerInterceptorAdapter oauthInterceptor() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        final OidcConstants.DynamicClientRegistrationMode mode =
                OidcConstants.DynamicClientRegistrationMode.valueOf(StringUtils.defaultIfBlank(
                        oidc.getDynamicClientRegistrationMode(),
                        OidcConstants.DynamicClientRegistrationMode.PROTECTED.name()));

        return new OidcHandlerInterceptorAdapter(requiresAuthenticationAccessTokenInterceptor,
                requiresAuthenticationAuthorizeInterceptor(),
                requiresAuthenticationDynamicRegistrationInterceptor(),
                mode);
    }

    @RefreshScope
    @Bean
    public Collection<BaseOidcScopeAttributeReleasePolicy> userDefinedScopeBasedAttributeReleasePolicies() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        return oidc.getUserDefinedScopes().entrySet()
                .stream()
                .map((k) -> new OidcCustomScopeAttributeReleasePolicy(k.getKey(), Arrays.asList(k.getValue().split(","))))
                .collect(Collectors.toSet());
    }

    @PostConstruct
    public void initOidcConfig() {
        this.initialAuthenticationAttemptWebflowEventResolver.addDelegate(oidcAuthenticationContextWebflowEventResolver());
    }
}
