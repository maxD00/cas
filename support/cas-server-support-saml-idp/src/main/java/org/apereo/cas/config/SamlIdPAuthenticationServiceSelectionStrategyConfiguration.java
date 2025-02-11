package org.apereo.cas.config;

import org.apereo.cas.authentication.AuthenticationServiceSelectionStrategy;
import org.apereo.cas.authentication.AuthenticationServiceSelectionStrategyConfigurer;
import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.ServiceFactoryConfigurer;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.support.CasFeatureModule;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.saml.authentication.SamlIdPServiceFactory;
import org.apereo.cas.support.saml.services.SamlIdPEntityIdAuthenticationServiceSelectionStrategy;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.spring.boot.ConditionalOnFeature;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * This is {@link SamlIdPAuthenticationServiceSelectionStrategyConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@EnableConfigurationProperties(CasConfigurationProperties.class)
@Configuration(value = "SamlIdPAuthenticationServiceSelectionStrategyConfiguration", proxyBeanMethods = false)
@ConditionalOnFeature(feature = CasFeatureModule.FeatureCatalog.SAMLIdentityProvider)
public class SamlIdPAuthenticationServiceSelectionStrategyConfiguration {

    @Configuration(value = "SamlIdPAuthenticationServiceSelectionConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class SamlIdPAuthenticationServiceSelectionConfiguration {
        @ConditionalOnMissingBean(name = "samlIdPEntityIdValidationServiceSelectionStrategy")
        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public AuthenticationServiceSelectionStrategy samlIdPEntityIdValidationServiceSelectionStrategy(
            final CasConfigurationProperties casProperties,
            @Qualifier("samlIdPServiceFactory")
            final ServiceFactory samlIdPServiceFactory,
            @Qualifier(ServicesManager.BEAN_NAME)
            final ServicesManager servicesManager) {
            return new SamlIdPEntityIdAuthenticationServiceSelectionStrategy(servicesManager,
                samlIdPServiceFactory, casProperties.getServer().getPrefix());
        }

        @Bean
        @ConditionalOnMissingBean(name = "samlIdPAuthenticationServiceSelectionStrategyConfigurer")
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public AuthenticationServiceSelectionStrategyConfigurer samlIdPAuthenticationServiceSelectionStrategyConfigurer(
            @Qualifier("samlIdPEntityIdValidationServiceSelectionStrategy")
            final AuthenticationServiceSelectionStrategy samlIdPEntityIdValidationServiceSelectionStrategy) {
            return plan -> plan.registerStrategy(samlIdPEntityIdValidationServiceSelectionStrategy);
        }
    }

    @Configuration(value = "SamlIdPAuthenticationServiceFactoryConfiguration", proxyBeanMethods = false)
    @EnableConfigurationProperties(CasConfigurationProperties.class)
    public static class SamlIdPAuthenticationServiceFactoryConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "samlIdPServiceFactory")
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public ServiceFactory samlIdPServiceFactory() {
            return new SamlIdPServiceFactory();
        }

        @Bean
        @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
        public ServiceFactoryConfigurer samlIdPServiceFactoryConfigurer(
            @Qualifier("samlIdPServiceFactory")
            final ServiceFactory samlIdPServiceFactory) {
            return () -> CollectionUtils.wrap(samlIdPServiceFactory);
        }
    }
}
