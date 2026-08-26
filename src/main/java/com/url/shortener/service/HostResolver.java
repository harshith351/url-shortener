package com.url.shortener.service;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Indirection over DNS so that SSRF rules can be unit tested deterministically,
 * without network access or reliance on any particular hostname resolving.
 */
@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
