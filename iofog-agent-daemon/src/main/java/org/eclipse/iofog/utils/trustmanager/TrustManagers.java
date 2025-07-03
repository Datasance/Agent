/*******************************************************************************
 * Copyright (c) 2021 Red Hat Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *   Jens Reimann
 *******************************************************************************/
package org.eclipse.iofog.utils.trustmanager;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLContext;
import java.security.SecureRandom;

public final class TrustManagers {

    private TrustManagers() {
    }

    private static void addAllX509(List<X509TrustManager> x509TrustManagers, TrustManager[] trustManagers) {
        for (TrustManager tm : trustManagers) {
            if (tm instanceof X509TrustManager) {
                x509TrustManagers.add((X509TrustManager) tm);
            }
        }
    }

    public static javax.net.ssl.TrustManager[] createTrustManager(final Certificate controllerCert) throws Exception {

        // the final list of trust managers

        final List<X509TrustManager> trustManagers = new ArrayList<>();

        // add the default system trust anchors

        {

            // create the trust manager factory using he default

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            // add the trust managers

            addAllX509(trustManagers, tmf.getTrustManagers());
        }

        // now add the specific controller certificate

        if (controllerCert != null) {

            // create the keystore

            KeyStore controllerCertStore = KeyStore.getInstance(KeyStore.getDefaultType());
            controllerCertStore.load(null, null);
            controllerCertStore.setCertificateEntry("cert", controllerCert);

            // create the trust manager factory

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(controllerCertStore);

            // add the trust managers

            addAllX509(trustManagers, tmf.getTrustManagers());

        }

        X509TrustManager combinedTrustManager = new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                CertificateException last = null;

                for (X509TrustManager tm : trustManagers) {
                    try {
                        tm.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException ex) {
                        last = ex;
                    }
                }

                throw new CertificateException("Unable to validate server certificate for controller connection", last);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates validation for controller is not supported");
            }
        };

        return new javax.net.ssl.TrustManager[]{combinedTrustManager};
    }

    public static javax.net.ssl.TrustManager[] createRouterTrustManager(final Certificate routerCert) throws Exception {

        // the final list of trust managers

        final List<X509TrustManager> trustManagers = new ArrayList<>();

        // add the default system trust anchors

        {

            // create the trust manager factory using he default

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            // add the trust managers

            addAllX509(trustManagers, tmf.getTrustManagers());
        }

        // now add the specific router certificate

        if (routerCert != null) {

            // create the keystore

            KeyStore routerCertStore = KeyStore.getInstance(KeyStore.getDefaultType());
            routerCertStore.load(null, null);
            routerCertStore.setCertificateEntry("cert", routerCert);

            // create the trust manager factory

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(routerCertStore);

            // add the trust managers

            addAllX509(trustManagers, tmf.getTrustManagers());

        }

        X509TrustManager combinedTrustManager = new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                CertificateException last = null;

                for (X509TrustManager tm : trustManagers) {
                    try {
                        tm.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException ex) {
                        last = ex;
                    }
                }

                throw new CertificateException("Unable to validate server certificate for router connection", last);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates validation for router is not supported");
            }
        };

        return new javax.net.ssl.TrustManager[]{combinedTrustManager};
    }

    public static javax.net.ssl.TrustManager[] createWebSocketTrustManager(final Certificate webSocketCert) throws Exception {

        // the final list of trust managers

        final List<X509TrustManager> trustManagers = new ArrayList<>();

        // add the default system trust anchors

        {

            // create the trust manager factory using he default

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            // add the trust managers

            addAllX509(trustManagers, tmf.getTrustManagers());
        }

        // now add the specific web socket certificate

        if (webSocketCert != null) {

            // create the keystore

            KeyStore webSocketCertStore = KeyStore.getInstance(KeyStore.getDefaultType());
            webSocketCertStore.load(null, null);
            webSocketCertStore.setCertificateEntry("cert", webSocketCert);

            // create the trust manager factory

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(webSocketCertStore);

            // add the trust managers

            addAllX509(trustManagers, tmf.getTrustManagers());

        }

        X509TrustManager combinedTrustManager = new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                CertificateException last = null;

                for (X509TrustManager tm : trustManagers) {
                    try {
                        tm.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException ex) {
                        last = ex;
                    }
                }

                throw new CertificateException("Unable to validate server certificate for websocket connection", last);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates validation for web socket is not supported");
            }
        };

        return new javax.net.ssl.TrustManager[]{combinedTrustManager};
    }

    /**
     * Creates an SSL socket factory that skips certificate verification
     * This is used when we need to make an insecure connection to get a new certificate
     * 
     * @return SSLConnectionSocketFactory configured to skip verification
     * @throws Exception if SSL context creation fails
     */
    public static org.apache.http.conn.ssl.SSLConnectionSocketFactory getInsecureSocketFactory() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        
        return new org.apache.http.conn.ssl.SSLConnectionSocketFactory(
            sslContext,
            new org.apache.http.conn.ssl.NoopHostnameVerifier()
        );
    }

}
