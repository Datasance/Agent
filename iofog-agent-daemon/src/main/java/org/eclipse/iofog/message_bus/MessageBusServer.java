/*
 * *******************************************************************************
 *  * Copyright (c) 2023 Datasance Teknoloji A.S.
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Eclipse Public License v. 2.0 which is available at
 *  * http://www.eclipse.org/legal/epl-2.0
 *  *
 *  * SPDX-License-Identifier: EPL-2.0
 *  *******************************************************************************
 *
 */
package org.eclipse.iofog.message_bus;

import org.apache.qpid.jms.JmsConnectionFactory;
import org.eclipse.iofog.exception.AgentSystemException;
import org.eclipse.iofog.microservice.Microservice;
import org.eclipse.iofog.utils.logging.LoggingService;
// import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.trustmanager.TrustManagers;

import jakarta.jms.*;
import jakarta.jms.IllegalStateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;
import java.io.IOException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.security.SecureRandom;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.io.ByteArrayInputStream;
import java.security.PrivateKey;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.InvalidKeyException;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PEMKeyPair;

/**
 * ActiveMQ server
 *
 * @author saeid
 */
public class MessageBusServer {

    public static final Object messageBusSessionLock = new Object();
    public static final Object consumerLock = new Object();
    public static final Object producerLock = new Object();
    private static final String MODULE_NAME = "Message Bus Server";

    private Connection connection;
    private static Session session;

    private Map<String, MessageConsumer> consumers = new ConcurrentHashMap<>();
    private Map<String, List<MessageProducer>> producers = new ConcurrentHashMap<>();

    private boolean isConnected = false;

	static TextMessage createMessage(String text) throws Exception {
		return session.createTextMessage(text);
	}

    /**
     * Sets {@link ExceptionListener}
     *
     * @param exceptionListener
     * @throws Exception
     */
    void setExceptionListener(ExceptionListener exceptionListener) throws Exception {
        if (connection != null) {
            connection.setExceptionListener(exceptionListener);
        }
    }

    /**
     * Starts ActiveMQ server
     *
     * @param routerHost - host of router
     * @param routerPort - port of router
     * @param caCert - CA certificate in PEM format
     * @param tlsCert - TLS certificate in PEM format
     * @param tlsKey - TLS private key in PEM format
     * @throws Exception
     */
    void startServer(String routerHost, int routerPort, String caCert, String tlsCert, String tlsKey) throws Exception {
        LoggingService.logDebug(MODULE_NAME, "Starting server");
        
        // Create SSL context using TrustManagers with CA certificate
        SSLContext sslContext = SSLContext.getInstance("TLS");
        TrustManager[] trustManagers = null;
        
        if (caCert != null && !caCert.trim().isEmpty()) {
            try {
                trustManagers = TrustManagers.createTrustManager(
                    CertificateFactory.getInstance("X.509").generateCertificate(
                        new ByteArrayInputStream(Base64.getDecoder().decode(caCert))));
            } catch (Exception e) {
                LoggingService.logWarning(MODULE_NAME, "Failed to parse CA certificate: " + e.getMessage());
                throw new AgentSystemException("Could not parse CA certificate", e);
            }
        }
        
        // Create keystore for client certificates if available
        KeyManager[] keyManagers = null;
        if (tlsCert != null && !tlsCert.trim().isEmpty() && 
            tlsKey != null && !tlsKey.trim().isEmpty()) {
            try {
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(null, null);
                
                // Parse the certificate
                Certificate cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(tlsCert)));
                
                // Parse the private key
                PrivateKey privateKey;
                try {
                    privateKey = getPrivateKeyFromBase64Pem(tlsKey);
                } catch (Exception e) {
                    LoggingService.logWarning(MODULE_NAME, "Failed to parse private key: " + e.getMessage());
                    throw new AgentSystemException("Could not parse private key", e);
                }
                
                // Add the certificate and private key to the keystore
                keyStore.setKeyEntry("client", privateKey, "".toCharArray(), new Certificate[]{cert});
                
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, "".toCharArray());
                keyManagers = kmf.getKeyManagers();
            } catch (Exception e) {
                LoggingService.logWarning(MODULE_NAME, "Failed to parse client certificates: " + e.getMessage());
                throw new AgentSystemException("Could not parse client certificates", e);
            }
        }
        
        // Initialize SSL context with available managers
        sslContext.init(keyManagers, trustManagers, new SecureRandom());
        
        // Configure connection factory with SSL
        JmsConnectionFactory connectionFactory = new JmsConnectionFactory(String.format("amqps://%s:%d", routerHost, routerPort));
        connectionFactory.setSslContext(sslContext);
        
        connection = connectionFactory.createConnection();
        LoggingService.logDebug(MODULE_NAME, "Finished starting server");
    }

    /**
     * creates IOFog {@link jakarta.jms.Message} producers
     * and {@link Session}
     *
     * @throws Exception
     */
    void initialize() throws Exception {
        LoggingService.logDebug(MODULE_NAME, "Starting initialization");
        synchronized (messageBusSessionLock) {
            session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            connection.start();
        }
        LoggingService.logDebug(MODULE_NAME, "Finished initialization");
    }

    /**
     * creates a new {@link MessageConsumer} for receiver {@link Microservice}
     *
     * @param name - ID of {@link Microservice}
     * @throws Exception
     */
    void createConsumer(String name) throws Exception {
        LoggingService.logDebug(MODULE_NAME, "Starting create consumer");

        synchronized (consumerLock) {
            Destination messageQueue = session.createQueue(name);
            MessageConsumer consumer = session.createConsumer(messageQueue);
            consumers.put(name, consumer);
        }

        LoggingService.logDebug(MODULE_NAME, "Finished create consumer");
    }

    /**
     * returns {@link MessageConsumer} of a receiver {@link Microservice}
     *
     * @param receiver - ID of {@link Microservice}
     * @return {@link MessageConsumer}
     */
    MessageConsumer getConsumer(String receiver) throws Exception {
        LoggingService.logDebug(MODULE_NAME, "Start get consumer");
        if (consumers == null || !consumers.containsKey(receiver))
            try {
                createConsumer(receiver);
            } catch (IllegalStateException e) {
                setConnected(false);
                throw e;
            }

        LoggingService.logDebug(MODULE_NAME, "Finished get consumer");
        return consumers.get(receiver);
    }

    /**
     * removes {@link MessageConsumer} when a receiver {@link Microservice} has been removed
     *
     * @param name - ID of {@link Microservice}
     */
    void removeConsumer(String name) throws Exception {
        LoggingService.logDebug(MODULE_NAME, "Start remove consumer");

        synchronized (consumerLock) {
            if (consumers != null && consumers.containsKey(name)) {
                MessageConsumer consumer = consumers.remove(name);
                consumer.close();
            }
        }

        LoggingService.logDebug(MODULE_NAME, "Finished remove consumer");
    }

    /**
     * creates a new {@link MessageProducer} for publisher {@link Microservice}
     *
     * @param name - ID of {@link Microservice}
     * @throws Exception
     */
    void createProducer(String name, List<String> receivers) throws Exception {
        LoggingService.logDebug(MODULE_NAME, "Start create Producer");

        synchronized (producerLock) {
            if (receivers != null && receivers.size() > 0) {
                List<MessageProducer> messageProducers = new ArrayList<>();
                for (String receiver: receivers) {
                    Destination messageQueue = session.createQueue(receiver);
                    MessageProducer producer = session.createProducer(messageQueue);
                    messageProducers.add(producer);
                }
                producers.put(name, messageProducers);
            }
        }

        LoggingService.logDebug(MODULE_NAME, "Finish create Producer");
    }

    /**
     * returns {@link MessageProducer} of a publisher {@link Microservice}
     *
     * @param publisher - ID of {@link Microservice}
     * @return {@link MessageProducer}
     */
    List<MessageProducer> getProducer(String publisher, List<String> receivers) throws Exception {
        LoggingService.logDebug(MODULE_NAME, "Start get Producer");

        if (!producers.containsKey(publisher)) {
            try {
                createProducer(publisher, receivers);
            } catch (IllegalStateException e) {
                setConnected(false);
                throw e;
            }
        }

        LoggingService.logDebug(MODULE_NAME, "Finish get Producer");
        return producers.get(publisher);
    }

    /**
     * removes {@link MessageConsumer} when a receiver {@link Microservice} has been removed
     *
     * @param name - ID of {@link Microservice}
     */
    void removeProducer(String name) {
        LoggingService.logDebug(MODULE_NAME, "Start remove Producer");

		synchronized (producerLock) {
			if (producers != null && producers.containsKey(name)) {
				List<MessageProducer> messageProducers = producers.remove(name);
				messageProducers.forEach(producer -> {
				    try {
				        producer.close();
                    } catch (Exception e) {
                        LoggingService.logWarning(MODULE_NAME, "Unable to close producer");
                    }
                });
			}
		}

        LoggingService.logDebug(MODULE_NAME, "Finish remove Producer");
    }

    /**
     * stops all consumers, producers and ActiveMQ server
     *
     * @throws Exception
     */
    void stopServer() throws Exception {
        LoggingService.logDebug(MODULE_NAME, "stopping server started");
        if (consumers != null) {
            consumers.forEach((key, value) -> {
                try {
                    value.close();
                } catch (Exception e) {
                    LoggingService.logError(MODULE_NAME, "Error closing consumer",
                            new AgentSystemException(e.getMessage(), e));
                }
            });
            consumers.clear();
        }
        if (producers != null) {
            producers.forEach((key, value) -> {
                value.forEach(producer -> {
                    try {
                        producer.close();
                    } catch (Exception e) {
                        LoggingService.logError(MODULE_NAME, "Error closing producer",
                                new AgentSystemException(e.getMessage(), e));
                    }
                });
            });
            producers.clear();
        }

        if (session != null) {
            session.close();
        }

        if (connection != null) {
            connection.close();
        }

        LoggingService.logDebug(MODULE_NAME, "stopped server");
    }

    public boolean isConnected() {
        synchronized (messageBusSessionLock) {
            return isConnected;
        }
    }

    public void setConnected(boolean connected) {
        synchronized (messageBusSessionLock) {
            isConnected = connected;
        }
    }

    private static PrivateKey getPrivateKeyFromBase64Pem(String base64Pem) throws Exception {
        // Decode the base64 string to get the PEM text
        byte[] pemBytes = Base64.getDecoder().decode(base64Pem);
        String pem = new String(pemBytes, StandardCharsets.UTF_8);

        // Parse the PEM
        try (PEMParser pemParser = new PEMParser(new StringReader(pem))) {
            Object object = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

            if (object instanceof PEMKeyPair) {
                return converter.getPrivateKey(((PEMKeyPair) object).getPrivateKeyInfo());
            } else if (object instanceof PrivateKeyInfo) {
                return converter.getPrivateKey((PrivateKeyInfo) object);
            } else {
                throw new IllegalArgumentException("Unsupported PEM object: " + object.getClass().getName());
            }
        }
    }
}
