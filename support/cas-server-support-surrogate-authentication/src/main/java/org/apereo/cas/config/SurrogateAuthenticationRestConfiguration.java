package org.apereo.cas.config;

import org.apereo.cas.authentication.rest.SurrogateAuthenticationRestHttpRequestCredentialFactory;
import org.apereo.cas.authentication.surrogate.SurrogateAuthenticationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.support.CasFeatureModule;
import org.apereo.cas.rest.plan.RestHttpRequestCredentialFactoryConfigurer;
import org.apereo.cas.util.spring.boot.ConditionalOnFeature;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * This is {@link SurrogateAuthenticationRestConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 6.2.0
 */
@Configuration(value = "SurrogateAuthenticationRestConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(CasConfigurationProperties.class)
@ConditionalOnClass(value = RestHttpRequestCredentialFactoryConfigurer.class)
@ConditionalOnFeature(feature = CasFeatureModule.FeatureCatalog.SurrogateAuthentication)
public class SurrogateAuthenticationRestConfiguration {

    /**
     * Override the core bean definition
     * that handles username+password to
     * avoid duplicate authentication attempts.
     *
     * @param surrogateAuthenticationService the surrogate authentication service
     * @param casProperties                  the cas properties
     * @return configurer instance
     */
    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    public RestHttpRequestCredentialFactoryConfigurer restHttpRequestCredentialFactoryConfigurer(
        @Qualifier("surrogateAuthenticationService")
        final SurrogateAuthenticationService surrogateAuthenticationService, final CasConfigurationProperties casProperties) {
        return factory -> factory.registerCredentialFactory(
            new SurrogateAuthenticationRestHttpRequestCredentialFactory(surrogateAuthenticationService, casProperties.getAuthn().getSurrogate()));
    }
}
