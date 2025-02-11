package org.apereo.cas.config;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.support.CasFeatureModule;
import org.apereo.cas.consent.ConsentRepository;
import org.apereo.cas.consent.LdapConsentRepository;
import org.apereo.cas.util.LdapUtils;
import org.apereo.cas.util.spring.boot.ConditionalOnFeature;

import lombok.val;
import org.ldaptive.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * This is {@link CasConsentLdapConfiguration}.
 *
 * @author Arnold Bergner
 * @since 5.2.0
 */
@Configuration(value = "CasConsentLdapConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(CasConfigurationProperties.class)
@ConditionalOnFeature(feature = CasFeatureModule.FeatureCatalog.Consent, module = "ldap")
public class CasConsentLdapConfiguration {

    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    public ConsentRepository consentRepository(
        final CasConfigurationProperties casProperties,
        @Qualifier("consentLdapConnectionFactory")
        final ConnectionFactory consentLdapConnectionFactory) {
        val ldap = casProperties.getConsent().getLdap();
        return new LdapConsentRepository(consentLdapConnectionFactory, ldap);
    }

    @Bean
    @RefreshScope(proxyMode = ScopedProxyMode.DEFAULT)
    @ConditionalOnMissingBean(name = "consentLdapConnectionFactory")
    public ConnectionFactory consentLdapConnectionFactory(final CasConfigurationProperties casProperties) {
        val ldap = casProperties.getConsent().getLdap();
        return LdapUtils.newLdaptiveConnectionFactory(ldap);
    }
}
