package org.apereo.cas.config;

import org.apereo.cas.CentralAuthenticationService;
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
import org.apereo.cas.mfa.simple.CasSimpleMultifactorAuthenticationHandler;
import org.apereo.cas.mfa.simple.CasSimpleMultifactorAuthenticationProvider;
import org.apereo.cas.mfa.simple.CasSimpleMultifactorTokenCredential;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.spring.boot.ConditionalOnFeature;

import lombok.val;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * This is {@link CasSimpleMultifactorAuthenticationEventExecutionPlanConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 6.0.0
 */
@Configuration(value = "CasSimpleMultifactorAuthenticationEventExecutionPlanConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(CasConfigurationProperties.class)
@ConditionalOnFeature(feature = CasFeatureModule.FeatureCatalog.SimpleMFA)
public class CasSimpleMultifactorAuthenticationEventExecutionPlanConfiguration {

    @ConditionalOnMissingBean(name = "casSimpleMultifactorAuthenticationHandler")
    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    public AuthenticationHandler casSimpleMultifactorAuthenticationHandler(
        @Qualifier("casSimpleMultifactorPrincipalFactory")
        final PrincipalFactory casSimpleMultifactorPrincipalFactory,
        @Qualifier(ServicesManager.BEAN_NAME)
        final ServicesManager servicesManager,
        @Qualifier(CentralAuthenticationService.BEAN_NAME)
        final CentralAuthenticationService centralAuthenticationService,
        final CasConfigurationProperties casProperties) {
        val props = casProperties.getAuthn().getMfa().getSimple();
        return new CasSimpleMultifactorAuthenticationHandler(props.getName(),
            servicesManager, casSimpleMultifactorPrincipalFactory,
            centralAuthenticationService, props.getOrder());
    }

    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    @ConditionalOnMissingBean(name = "casSimpleMultifactorAuthenticationProvider")
    public MultifactorAuthenticationProvider casSimpleMultifactorAuthenticationProvider(
        @Qualifier("casSimpleMultifactorBypassEvaluator")
        final MultifactorAuthenticationProviderBypassEvaluator casSimpleMultifactorBypassEvaluator,
        @Qualifier("failureModeEvaluator")
        final MultifactorAuthenticationFailureModeEvaluator failureModeEvaluator,
        final CasConfigurationProperties casProperties) {
        val simple = casProperties.getAuthn().getMfa().getSimple();
        val p = new CasSimpleMultifactorAuthenticationProvider();
        p.setBypassEvaluator(casSimpleMultifactorBypassEvaluator);
        p.setFailureMode(simple.getFailureMode());
        p.setFailureModeEvaluator(failureModeEvaluator);
        p.setOrder(simple.getRank());
        p.setId(simple.getId());
        return p;
    }

    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    @ConditionalOnMissingBean(name = "casSimpleMultifactorAuthenticationMetaDataPopulator")
    public AuthenticationMetaDataPopulator casSimpleMultifactorAuthenticationMetaDataPopulator(
        @Qualifier("casSimpleMultifactorAuthenticationProvider")
        final MultifactorAuthenticationProvider casSimpleMultifactorAuthenticationProvider,
        @Qualifier("casSimpleMultifactorAuthenticationHandler")
        final AuthenticationHandler casSimpleMultifactorAuthenticationHandler,
        final CasConfigurationProperties casProperties) {
        return new AuthenticationContextAttributeMetaDataPopulator(
            casProperties.getAuthn().getMfa().getCore().getAuthenticationContextAttribute(),
            casSimpleMultifactorAuthenticationHandler,
            casSimpleMultifactorAuthenticationProvider.getId()
        );
    }

    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    @ConditionalOnMissingBean(name = "casSimpleMultifactorProviderAuthenticationMetadataPopulator")
    public AuthenticationMetaDataPopulator casSimpleMultifactorProviderAuthenticationMetadataPopulator(
        @Qualifier(ServicesManager.BEAN_NAME)
        final ServicesManager servicesManager,
        final CasConfigurationProperties casProperties,
        @Qualifier("casSimpleMultifactorAuthenticationProvider")
        final MultifactorAuthenticationProvider casSimpleMultifactorAuthenticationProvider) {
        val authenticationContextAttribute = casProperties.getAuthn().getMfa().getCore().getAuthenticationContextAttribute();
        return new MultifactorAuthenticationProviderMetadataPopulator(authenticationContextAttribute,
            casSimpleMultifactorAuthenticationProvider, servicesManager);
    }

    @ConditionalOnMissingBean(name = "casSimpleMultifactorPrincipalFactory")
    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    public PrincipalFactory casSimpleMultifactorPrincipalFactory() {
        return PrincipalFactoryUtils.newPrincipalFactory();
    }

    @ConditionalOnMissingBean(name = "casSimpleMultifactorAuthenticationEventExecutionPlanConfigurer")
    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    public AuthenticationEventExecutionPlanConfigurer casSimpleMultifactorAuthenticationEventExecutionPlanConfigurer(
        @Qualifier("casSimpleMultifactorProviderAuthenticationMetadataPopulator")
        final AuthenticationMetaDataPopulator casSimpleMultifactorProviderAuthenticationMetadataPopulator,
        @Qualifier("casSimpleMultifactorAuthenticationHandler")
        final AuthenticationHandler casSimpleMultifactorAuthenticationHandler,
        @Qualifier("casSimpleMultifactorAuthenticationMetaDataPopulator")
        final AuthenticationMetaDataPopulator casSimpleMultifactorAuthenticationMetaDataPopulator) {
        return plan -> {
            plan.registerAuthenticationHandler(casSimpleMultifactorAuthenticationHandler);
            plan.registerAuthenticationMetadataPopulator(casSimpleMultifactorAuthenticationMetaDataPopulator);
            plan.registerAuthenticationMetadataPopulator(casSimpleMultifactorProviderAuthenticationMetadataPopulator);
            plan.registerAuthenticationHandlerResolver(new ByCredentialTypeAuthenticationHandlerResolver(CasSimpleMultifactorTokenCredential.class));
        };
    }
}
