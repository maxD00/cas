package org.apereo.cas.config;

import org.apereo.cas.authentication.AuthenticationEventExecutionPlanConfigurer;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.AuthenticationMetaDataPopulator;
import org.apereo.cas.authentication.MultifactorAuthenticationFailureModeEvaluator;
import org.apereo.cas.authentication.MultifactorAuthenticationProvider;
import org.apereo.cas.authentication.bypass.MultifactorAuthenticationProviderBypassEvaluator;
import org.apereo.cas.authentication.handler.ByCredentialTypeAuthenticationHandlerResolver;
import org.apereo.cas.authentication.metadata.AuthenticationContextAttributeMetaDataPopulator;
import org.apereo.cas.authentication.metadata.MultifactorAuthenticationProviderMetadataPopulator;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.PrincipalFactoryUtils;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.support.CasFeatureModule;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.cipher.CipherExecutorUtils;
import org.apereo.cas.util.crypto.CipherExecutor;
import org.apereo.cas.util.spring.beans.BeanCondition;
import org.apereo.cas.util.spring.beans.BeanSupplier;
import org.apereo.cas.util.spring.boot.ConditionalOnFeature;
import org.apereo.cas.web.CasWebSecurityConstants;
import org.apereo.cas.web.ProtocolEndpointWebSecurityConfigurer;
import org.apereo.cas.webauthn.WebAuthnAuthenticationHandler;
import org.apereo.cas.webauthn.WebAuthnCredential;
import org.apereo.cas.webauthn.WebAuthnCredentialRegistrationCipherExecutor;
import org.apereo.cas.webauthn.WebAuthnMultifactorAuthenticationProvider;
import org.apereo.cas.webauthn.WebAuthnUtils;
import org.apereo.cas.webauthn.storage.JsonResourceWebAuthnCredentialRepository;
import org.apereo.cas.webauthn.storage.WebAuthnCredentialRepository;
import org.apereo.cas.webauthn.web.WebAuthnController;
import org.apereo.cas.webauthn.web.WebAuthnRegisteredDevicesEndpoint;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yubico.core.DefaultSessionManager;
import com.yubico.core.SessionManager;
import com.yubico.core.WebAuthnServer;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.attestation.AttestationResolver;
import com.yubico.webauthn.attestation.MetadataObject;
import com.yubico.webauthn.attestation.MetadataService;
import com.yubico.webauthn.attestation.StandardMetadataService;
import com.yubico.webauthn.attestation.TrustResolver;
import com.yubico.webauthn.attestation.resolver.CompositeAttestationResolver;
import com.yubico.webauthn.attestation.resolver.CompositeTrustResolver;
import com.yubico.webauthn.attestation.resolver.SimpleAttestationResolver;
import com.yubico.webauthn.attestation.resolver.SimpleTrustResolverWithEquality;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.extension.appid.AppId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jooq.lambda.Unchecked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * This is {@link WebAuthnConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 6.1.0
 */
@Slf4j
@EnableConfigurationProperties(CasConfigurationProperties.class)
@ConditionalOnFeature(feature = CasFeatureModule.FeatureCatalog.WebAuthn)
@Configuration(value = "WebAuthnConfiguration", proxyBeanMethods = false)
public class WebAuthnConfiguration {
    private static final BeanCondition CONDITION = BeanCondition.on("cas.authn.mfa.web-authn.core.enabled").isTrue().evenIfMissing();

    private static final int CACHE_MAX_SIZE = 10_000;

    private static <K, V> Cache<K, V> newCache() {
        return CacheBuilder.newBuilder().maximumSize(CACHE_MAX_SIZE).expireAfterAccess(Duration.ofMinutes(5)).build();
    }

    @Configuration(value = "WebAuthnMetadataServiceConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnMetadataServiceConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "webAuthnMetadataService")
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public MetadataService webAuthnMetadataService(
            final ConfigurableApplicationContext applicationContext,
            final CasConfigurationProperties casProperties,
            final ObjectProvider<List<TrustResolver>> foundTrustResolvers,
            final ObjectProvider<List<AttestationResolver>> foundAttestations) throws Exception {

            return BeanSupplier.of(MetadataService.class)
                .when(CONDITION.given(applicationContext.getEnvironment()))
                .supply(Unchecked.supplier(() -> {
                    val trustResolvers = new ArrayList<TrustResolver>();
                    trustResolvers.add(StandardMetadataService.createDefaultTrustResolver());
                    trustResolvers.addAll(Optional.ofNullable(foundTrustResolvers.getIfAvailable()).orElseGet(ArrayList::new));
                    val trustResolver = new CompositeTrustResolver(trustResolvers);
                    val attestationResolvers = new ArrayList<AttestationResolver>();
                    attestationResolvers.add(StandardMetadataService.createDefaultAttestationResolver(trustResolver));
                    val resource = casProperties.getAuthn().getMfa().getWebAuthn().getCore().getTrustedDeviceMetadata().getLocation();
                    if (resource != null) {
                        val metadata = WebAuthnUtils.getObjectMapper().readValue(resource.getInputStream(), MetadataObject.class);
                        attestationResolvers.add(new SimpleAttestationResolver(CollectionUtils.wrapList(metadata), trustResolver));
                    }
                    attestationResolvers.addAll(Optional.ofNullable(foundAttestations.getIfAvailable()).orElseGet(ArrayList::new));
                    val attestationResolver = new CompositeAttestationResolver(attestationResolvers);
                    return new StandardMetadataService(attestationResolver);
                }))
                .otherwiseProxy()
                .get();
        }

    }

    @Configuration(value = "WebAuthnCoreConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnCoreConfiguration {
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        @ConditionalOnMissingBean(name = "simpleTrustResolverWithEquality")
        public TrustResolver simpleTrustResolverWithEquality() {
            return new SimpleTrustResolverWithEquality(new ArrayList<>());
        }

        @Bean
        @ConditionalOnMissingBean(name = "webAuthnSessionManager")
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public SessionManager webAuthnSessionManager(final ConfigurableApplicationContext applicationContext) throws Exception {
            return BeanSupplier.of(SessionManager.class)
                .when(CONDITION.given(applicationContext.getEnvironment()))
                .supply(DefaultSessionManager::new)
                .otherwiseProxy()
                .get();
        }

        @ConditionalOnMissingBean(name = "webAuthnPrincipalFactory")
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public PrincipalFactory webAuthnPrincipalFactory() {
            return PrincipalFactoryUtils.newPrincipalFactory();
        }
    }

    @Configuration(value = "WebAuthnSchedulerConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnSchedulerConfiguration {
        @ConditionalOnMissingBean(name = "webAuthnDeviceRepositoryCleanerScheduler")
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public Runnable webAuthnDeviceRepositoryCleanerScheduler(
            final ConfigurableApplicationContext applicationContext,
            @Qualifier("webAuthnCredentialRepository")
            final WebAuthnCredentialRepository webAuthnCredentialRepository) throws Exception {
            return BeanSupplier.of(Runnable.class)
                .when(BeanCondition.on("cas.authn.mfa.web-authn.cleaner.enabled").isTrue().evenIfMissing().given(applicationContext.getEnvironment()))
                .supply(() -> new WebAuthnDeviceRepositoryCleanerScheduler(webAuthnCredentialRepository))
                .otherwiseProxy()
                .get();
        }
    }

    @RequiredArgsConstructor
    public static class WebAuthnDeviceRepositoryCleanerScheduler implements Runnable {

        private final WebAuthnCredentialRepository repository;

        @Scheduled(initialDelayString = "${cas.authn.mfa.web-authn.cleaner.schedule.start-delay:PT20S}",
            fixedDelayString = "${cas.authn.mfa.web-authn.cleaner.schedule.repeat-interval:PT5M}")
        @Override
        public void run() {
            LOGGER.debug("Starting to clean expired devices from repository");
            repository.clean();
        }
    }

    @Configuration(value = "WebAuthnServerConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnServerConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "webAuthnServer")
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public WebAuthnServer webAuthnServer(
            final CasConfigurationProperties casProperties,
            @Qualifier("webAuthnCredentialRepository")
            final WebAuthnCredentialRepository webAuthnCredentialRepository,
            @Qualifier("webAuthnMetadataService")
            final MetadataService webAuthnMetadataService,
            @Qualifier("webAuthnSessionManager")
            final SessionManager webAuthnSessionManager) throws Exception {
            val webAuthn = casProperties.getAuthn().getMfa().getWebAuthn().getCore();
            val serverName = casProperties.getServer().getName();
            val appId = new AppId(StringUtils.defaultString(webAuthn.getApplicationId(), serverName));
            val defaultRelyingPartyId = RelyingPartyIdentity.builder()
                .id(StringUtils.defaultString(webAuthn.getRelyingPartyId(), new URL(serverName).getHost()))
                .name(StringUtils.defaultString(webAuthn.getRelyingPartyName(), "CAS")).build();
            val origins = new LinkedHashSet<String>();
            if (StringUtils.isNotBlank(webAuthn.getAllowedOrigins())) {
                origins.addAll(org.springframework.util.StringUtils.commaDelimitedListToSet(webAuthn.getAllowedOrigins()));
            } else {
                origins.add(serverName);
            }
            val conveyance = AttestationConveyancePreference.valueOf(webAuthn.getAttestationConveyancePreference().toUpperCase());
            val relyingParty = RelyingParty.builder().identity(defaultRelyingPartyId)
                .credentialRepository(webAuthnCredentialRepository)
                .origins(origins).attestationConveyancePreference(conveyance)
                .metadataService(webAuthnMetadataService)
                .allowUnrequestedExtensions(webAuthn.isAllowUnrequestedExtensions())
                .allowUntrustedAttestation(webAuthn.isAllowUntrustedAttestation())
                .validateSignatureCounter(webAuthn.isValidateSignatureCounter()).appId(appId).build();
            return new WebAuthnServer(webAuthnCredentialRepository, newCache(), newCache(), relyingParty, webAuthnSessionManager);
        }
    }

    @Configuration(value = "WebAuthnHandlerConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnHandlerConfiguration {
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        @ConditionalOnMissingBean(name = "webAuthnAuthenticationHandler")
        public AuthenticationHandler webAuthnAuthenticationHandler(
            final ConfigurableApplicationContext applicationContext,
            final CasConfigurationProperties casProperties,
            @Qualifier("webAuthnPrincipalFactory")
            final PrincipalFactory webAuthnPrincipalFactory,
            @Qualifier("webAuthnCredentialRepository")
            final WebAuthnCredentialRepository webAuthnCredentialRepository,
            @Qualifier("webAuthnSessionManager")
            final SessionManager webAuthnSessionManager,
            @Qualifier(ServicesManager.BEAN_NAME)
            final ServicesManager servicesManager) throws Exception {
            return BeanSupplier.of(AuthenticationHandler.class)
                .when(CONDITION.given(applicationContext.getEnvironment()))
                .supply(() -> {
                    val webAuthn = casProperties.getAuthn().getMfa().getWebAuthn();
                    return new WebAuthnAuthenticationHandler(webAuthn.getName(), servicesManager, webAuthnPrincipalFactory,
                        webAuthnCredentialRepository, webAuthnSessionManager, webAuthn.getOrder());
                })
                .otherwiseProxy()
                .get();
        }
    }

    @Configuration(value = "WebAuthnMetadataConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnMetadataConfiguration {
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        @ConditionalOnMissingBean(name = "webAuthnMultifactorProviderAuthenticationMetadataPopulator")
        public AuthenticationMetaDataPopulator webAuthnMultifactorProviderAuthenticationMetadataPopulator(
            @Qualifier(ServicesManager.BEAN_NAME)
            final ServicesManager servicesManager,
            final CasConfigurationProperties casProperties,
            @Qualifier("webAuthnMultifactorAuthenticationProvider")
            final MultifactorAuthenticationProvider webAuthnMultifactorAuthenticationProvider) {
            val authenticationContextAttribute = casProperties.getAuthn().getMfa().getCore().getAuthenticationContextAttribute();
            return new MultifactorAuthenticationProviderMetadataPopulator(authenticationContextAttribute,
                webAuthnMultifactorAuthenticationProvider, servicesManager);
        }

        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        @ConditionalOnMissingBean(name = "webAuthnAuthenticationMetaDataPopulator")
        public AuthenticationMetaDataPopulator webAuthnAuthenticationMetaDataPopulator(
            final ConfigurableApplicationContext applicationContext,
            final CasConfigurationProperties casProperties,
            @Qualifier("webAuthnAuthenticationHandler")
            final AuthenticationHandler webAuthnAuthenticationHandler,
            @Qualifier("webAuthnMultifactorAuthenticationProvider")
            final MultifactorAuthenticationProvider webAuthnMultifactorAuthenticationProvider) throws Exception {
            return BeanSupplier.of(AuthenticationMetaDataPopulator.class)
                .when(CONDITION.given(applicationContext.getEnvironment()))
                .supply(() -> {
                    val authenticationContextAttribute = casProperties.getAuthn().getMfa().getCore().getAuthenticationContextAttribute();
                    return new AuthenticationContextAttributeMetaDataPopulator(authenticationContextAttribute,
                        webAuthnAuthenticationHandler, webAuthnMultifactorAuthenticationProvider.getId());
                })
                .otherwiseProxy()
                .get();
        }
    }

    @Configuration(value = "WebAuthnAuthenticationPlanConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnAuthenticationPlanConfiguration {
        @ConditionalOnMissingBean(name = "webAuthnAuthenticationEventExecutionPlanConfigurer")
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public AuthenticationEventExecutionPlanConfigurer webAuthnAuthenticationEventExecutionPlanConfigurer(
            final ConfigurableApplicationContext applicationContext,
            @Qualifier("webAuthnMultifactorProviderAuthenticationMetadataPopulator")
            final AuthenticationMetaDataPopulator webAuthnMultifactorProviderAuthenticationMetadataPopulator,
            @Qualifier("webAuthnAuthenticationHandler")
            final AuthenticationHandler webAuthnAuthenticationHandler,
            @Qualifier("webAuthnAuthenticationMetaDataPopulator")
            final AuthenticationMetaDataPopulator webAuthnAuthenticationMetaDataPopulator) throws Exception {
            return BeanSupplier.of(AuthenticationEventExecutionPlanConfigurer.class)
                .when(CONDITION.given(applicationContext.getEnvironment()))
                .supply(() -> plan -> {
                    plan.registerAuthenticationHandler(webAuthnAuthenticationHandler);
                    plan.registerAuthenticationMetadataPopulator(webAuthnAuthenticationMetaDataPopulator);
                    plan.registerAuthenticationMetadataPopulator(webAuthnMultifactorProviderAuthenticationMetadataPopulator);
                    plan.registerAuthenticationHandlerResolver(new ByCredentialTypeAuthenticationHandlerResolver(WebAuthnCredential.class));
                })
                .otherwiseProxy()
                .get();

        }
    }

    @Configuration(value = "WebAuthnRepositoryConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class WebAuthnRepositoryConfiguration {
        @ConditionalOnMissingBean(name = "webAuthnCredentialRepository")
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public WebAuthnCredentialRepository webAuthnCredentialRepository(
            final ConfigurableApplicationContext applicationContext,
            final CasConfigurationProperties casProperties,
            @Qualifier("webAuthnCredentialRegistrationCipherExecutor")
            final CipherExecutor webAuthnCredentialRegistrationCipherExecutor) throws Exception {
            return BeanSupplier.of(WebAuthnCredentialRepository.class)
                .when(CONDITION.given(applicationContext.getEnvironment()))
                .supply(() -> {
                    val webauthn = casProperties.getAuthn().getMfa().getWebAuthn();
                    val location = webauthn.getJson().getLocation();
                    if (location != null) {
                        return new JsonResourceWebAuthnCredentialRepository(casProperties, location, webAuthnCredentialRegistrationCipherExecutor);
                    }
                    return WebAuthnCredentialRepository.inMemory();
                })
                .otherwiseProxy()
                .get();
        }

        @Configuration(value = "WebAuthnMultifactorProviderConfiguration", proxyBeanMethods = false)
        @EnableConfigurationProperties(CasConfigurationProperties.class)
        public static class WebAuthnMultifactorProviderConfiguration {
            @ConditionalOnMissingBean(name = "webAuthnMultifactorAuthenticationProvider")
            @Bean
            @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
            public MultifactorAuthenticationProvider webAuthnMultifactorAuthenticationProvider(
                final ConfigurableApplicationContext applicationContext,
                final CasConfigurationProperties casProperties,
                @Qualifier("failureModeEvaluator")
                final MultifactorAuthenticationFailureModeEvaluator failureModeEvaluator,
                @Qualifier("webAuthnBypassEvaluator")
                final MultifactorAuthenticationProviderBypassEvaluator webAuthnBypassEvaluator) throws Exception {
                return BeanSupplier.of(MultifactorAuthenticationProvider.class)
                    .when(CONDITION.given(applicationContext.getEnvironment()))
                    .supply(() -> {
                        val webauthn = casProperties.getAuthn().getMfa().getWebAuthn();
                        val p = new WebAuthnMultifactorAuthenticationProvider();
                        p.setBypassEvaluator(webAuthnBypassEvaluator);
                        p.setFailureMode(webauthn.getFailureMode());
                        p.setFailureModeEvaluator(failureModeEvaluator);
                        p.setOrder(webauthn.getRank());
                        p.setId(webauthn.getId());
                        return p;
                    })
                    .otherwiseProxy()
                    .get();
            }
        }

        @Configuration(value = "WebAuthnControllerConfiguration", proxyBeanMethods = false)
        @EnableConfigurationProperties(CasConfigurationProperties.class)
        public static class WebAuthnControllerConfiguration {
            @Bean
            @ConditionalOnAvailableEndpoint
            @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
            public WebAuthnRegisteredDevicesEndpoint webAuthnRegisteredDevicesEndpoint(
                final CasConfigurationProperties casProperties,
                @Qualifier("webAuthnCredentialRepository")
                final ObjectProvider<WebAuthnCredentialRepository> webAuthnCredentialRepository) {
                return new WebAuthnRegisteredDevicesEndpoint(casProperties, webAuthnCredentialRepository);
            }

            @ConditionalOnMissingBean(name = "webAuthnController")
            @Bean
            @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
            public WebAuthnController webAuthnController(
                @Qualifier("webAuthnServer")
                final WebAuthnServer webAuthnServer) {
                return new WebAuthnController(webAuthnServer);
            }

        }

        @Configuration(value = "WebAuthnCryptoConfiguration", proxyBeanMethods = false)
        @EnableConfigurationProperties(CasConfigurationProperties.class)
        public static class WebAuthnCryptoConfiguration {
            @ConditionalOnMissingBean(name = "webAuthnCredentialRegistrationCipherExecutor")
            @Bean
            @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
            public CipherExecutor webAuthnCredentialRegistrationCipherExecutor(
                final ConfigurableApplicationContext applicationContext,
                final CasConfigurationProperties casProperties) throws Exception {
                return BeanSupplier.of(CipherExecutor.class)
                    .when(CONDITION.given(applicationContext.getEnvironment()))
                    .supply(() -> {
                        val crypto = casProperties.getAuthn().getMfa().getWebAuthn().getCrypto();
                        if (crypto.isEnabled()) {
                            return CipherExecutorUtils.newStringCipherExecutor(crypto, WebAuthnCredentialRegistrationCipherExecutor.class);
                        }
                        LOGGER.trace("Web Authn credential registration records managed by CAS are not signed/encrypted.");
                        return CipherExecutor.noOp();
                    })
                    .otherwiseProxy()
                    .get();
            }
        }

        @Configuration(value = "WebAuthnSecurityConfiguration", proxyBeanMethods = false)
        @Order(CasWebSecurityConstants.SECURITY_CONFIGURATION_ORDER - 1)
        public static class WebAuthnSecurityConfiguration {

            @Bean
            @ConditionalOnMissingBean(name = "webAuthnCsrfTokenRepository")
            @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
            public CsrfTokenRepository webAuthnCsrfTokenRepository() {
                return new HttpSessionCsrfTokenRepository();
            }

            @Bean
            @ConditionalOnMissingBean(name = "webAuthnProtocolEndpointConfigurer")
            @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
            public ProtocolEndpointWebSecurityConfigurer<HttpSecurity> webAuthnProtocolEndpointConfigurer(
                @Qualifier("webAuthnCsrfTokenRepository")
                final ObjectProvider<CsrfTokenRepository> webAuthnCsrfTokenRepository) {
                return new ProtocolEndpointWebSecurityConfigurer<>() {
                    @Override
                    public ProtocolEndpointWebSecurityConfigurer<HttpSecurity> configure(final HttpSecurity http) {
                        Unchecked.consumer(sec -> http.csrf(customizer -> {
                            val pattern = new AntPathRequestMatcher(WebAuthnController.BASE_ENDPOINT_WEBAUTHN + "/**");
                            webAuthnCsrfTokenRepository.ifAvailable(
                                repository -> customizer.requireCsrfProtectionMatcher(pattern).csrfTokenRepository(repository));
                        })).accept(http);
                        return this;
                    }
                };
            }
        }
    }
}
