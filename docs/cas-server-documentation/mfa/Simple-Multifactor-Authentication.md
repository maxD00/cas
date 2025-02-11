---
layout: default
title: CAS - Simple Multifactor Authentication
category: Multifactor Authentication
---

{% include variables.html %}

# Simple Multifactor Authentication

Allow CAS to act as a multifactor authentication provider on its own, issuing tokens and sending them to end-users via pre-defined communication channels such as email 
or text messages. Tokens issued by CAS are tracked using the [ticket registry](../ticketing/Configuring-Ticketing-Components.html)
and are assigned a configurable expiration policy controlled via CAS settings.

## Configuration

Support is enabled by including the following module in the overlay:

{% include_cached casmodule.html group="org.apereo.cas" module="cas-server-support-simple-mfa" %}

{% include_cached casproperties.html properties="cas.authn.mfa.simple" excludes=".mail,.sms,.bucket4j" %}

## Registration

Registration is expected to have occurred as an out-of-band process. Ultimately, 
CAS expects to fetch the necessary attributes
from configured attribute sources to determine communications channels for 
email and/or sms. The adopter is expected to have populated
user records with enough information to indicate a phone number and/or email 
address where CAS could then be configured to fetch and
examine those attributes to share generated tokens.

## Communication Strategy

Users may be notified of CAS-issued tokens via text messages and/or email. The 
authenticated CAS principal is expected to carry enough attributes, 
configurable via CAS settings, in order for CAS to properly send text messages 
and/or email to the end-user. Tokens may also be shared
via notification strategies back by platforms such as Google Firebase, etc.

To learn more about available options, please [see this guide](../notifications/SMS-Messaging-Configuration.html) 
or [this guide](../notifications/Sending-Email-Configuration.html), or [this guide](../notifications/Notifications-Configuration.html).

{% include_cached casproperties.html properties="cas.authn.mfa.simple.mail,cas.authn.mfa.simple.sms" %}

## Rate Limiting

CAS is able to support rate-limiting for token requests based on the token-bucket 
algorithm, via the [Bucket4j](https://bucket4j.com/) project. This
means that token requests that reach a certain configurable capacity within
a time window may either be blocked or _throttled_ to slow down. This is done to
protect the system from overloading, allowing you to introduce a scenario to allow
CAS `120` token requests per minute with a refill rate of `10` requests per
second that would continually increase in the capacity bucket. Please note that the bucket allocation 
strategy is specific to the client IP address.

{% include_cached casproperties.html properties="cas.authn.mfa.simple.bucket4j" %}
