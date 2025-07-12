FROM docker.io/library/ubuntu:22.04 AS builder

RUN apt-get update && \
    apt-get install -y unzip apt-utils curl openjdk-17-jdk && \
    apt-get clean

# 1- Define a constant with the version of gradle you want to install
ARG GRADLE_VERSION=8.4

# 2- Define the URL where gradle can be downloaded from
ARG GRADLE_BASE_URL=https://services.gradle.org/distributions

# 3- Define the SHA key to validate the gradle download
#    obtained from here https://gradle.org/release-checksums/
ARG GRADLE_SHA=3e1af3ae886920c3ac87f7a91f816c0c7c436f276a6eefdb3da152100fef72ae

# 4- Create the directories, download gradle, validate the download, install it, remove downloaded file and set links
RUN mkdir -p /usr/share/gradle /usr/share/gradle/ref \
  && echo "Downlaoding gradle hash" \
  && curl -fsSL -o /tmp/gradle.zip ${GRADLE_BASE_URL}/gradle-${GRADLE_VERSION}-bin.zip \
  \
  && echo "Checking download hash" \
  && echo "${GRADLE_SHA}  /tmp/gradle.zip" | sha256sum -c - \
  \
  && echo "Unziping gradle" \
  && unzip -d /usr/share/gradle /tmp/gradle.zip \
   \
  && echo "Cleaning and setting links" \
  && rm -f /tmp/gradle.zip \
  && ln -s /usr/share/gradle/gradle-${GRADLE_VERSION} /usr/bin/gradle

# 5- Define environmental variables required by gradle
ENV GRADLE_VERSION=8.4
ENV GRADLE_HOME=/usr/bin/gradle
ENV GRADLE_USER_HOME=/cache
ENV PATH=$PATH:$GRADLE_HOME/bin

VOLUME $GRADLE_USER_HOME

COPY . .

RUN gradle build -x test --no-daemon
RUN gradle copy -x test --no-daemon

FROM eclipse-temurin:17-jdk-ubi9-minimal AS jre-build

COPY --from=builder packaging/iofog-agent/usr ./usr

# Extract and analyze dependencies for the iofog-agent JAR
RUN jar xf /usr/bin/iofog-agent.jar && \
    jdeps \
        --ignore-missing-deps \
        --print-module-deps \
        --multi-release 17 \
        --recursive \
        --class-path 'BOOT-INF/lib/*' \
        /usr/bin/iofog-agent.jar > iofog-agent.txt

# Extract and analyze dependencies for the iofog-agentd JAR
RUN jar xf /usr/bin/iofog-agentd.jar && \
    jdeps \
        --ignore-missing-deps \
        --print-module-deps \
        --multi-release 17 \
        --recursive \
        --class-path 'BOOT-INF/lib/*' \
        /usr/bin/iofog-agentd.jar > iofog-agentd.txt

# Extract and analyze dependencies for the iofog-agentvc JAR
RUN jar xf /usr/bin/iofog-agentvc.jar && \
    jdeps \
        --ignore-missing-deps \
        --print-module-deps \
        --multi-release 17 \
        --recursive \
        --class-path 'BOOT-INF/lib/*' \
        /usr/bin/iofog-agentvc.jar > iofog-agentvc.txt

# Merge the dependency files, remove duplicates, and format properly
RUN cat iofog-agent.txt iofog-agentd.txt iofog-agentvc.txt | \
    sort | uniq | paste -sd "," - > modules.txt

# Create a custom Java runtime
RUN $JAVA_HOME/bin/jlink \
         --add-modules $(cat modules.txt) \
         --add-modules jdk.crypto.ec \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /javaruntime

# Base builder stage for dependencies using UBI Minimal
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest AS ubi-dep

# Install necessary dependencies
RUN true && \
    microdnf install -y ca-certificates shadow-utils gzip procps-ng && \
    microdnf install -y tzdata && microdnf reinstall -y tzdata\
    microdnf clean all && \ 
    rm -rf /var/cache/* && \
    true

RUN true && \
    useradd -r -U -s /usr/bin/nologin iofog-agent && \
    usermod -aG root,wheel iofog-agent && \
    true

# Final stage using UBI Micro
FROM registry.access.redhat.com/ubi9/ubi-micro:latest

# Copy dependencies from the ubi-dep stage
COPY --from=ubi-dep /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=ubi-dep /usr/bin/curl /usr/bin/
COPY --from=ubi-dep /usr/bin/grep /usr/bin/
COPY --from=ubi-dep /usr/bin/gzip /usr/bin/
COPY --from=ubi-dep /usr/bin/pgrep /usr/bin/
COPY --from=ubi-dep /usr/bin/awk /usr/bin/
COPY --from=ubi-dep /etc/ssl/certs/ca-bundle.crt /etc/ssl/certs/
COPY --from=ubi-dep /etc/pki/tls/certs/ca-bundle.crt /etc/pki/tls/certs/

# Copy required shared libraries for curl grep awk
COPY --from=ubi-dep /usr/lib64/libc.so.6 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libcom_err.so.2 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libcrypto.so.3 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libcurl.so.4 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libffi.so.8 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libgmp.so.10 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libgnutls.so.30 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libgssapi_krb5.so.2 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libpcre.so.1 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libhogweed.so.6 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libidn2.so.0 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libk5crypto.so.3 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libkeyutils.so.1 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libkrb5.so.3 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libkrb5support.so.0 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libnettle.so.8 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libnghttp2.so.14 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libp11-kit.so.0 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libresolv.so.2 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libssl.so.3 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libtasn1.so.6 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libunistring.so.2 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libz.so.1 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libzstd.so.1 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libm.so.6 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libmpfr.so.6 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libreadline.so.8 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libsigsegv.so.2 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libtinfo.so.6 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libprocps.so.8 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libsystemd.so.0 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/liblz4.so.1 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libcap.so.2  /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libgcrypt.so.20  /usr/lib64/
COPY --from=ubi-dep /usr/lib64/libgpg-error.so.0 /usr/lib64/
COPY --from=ubi-dep /usr/lib64/liblzma.so.5 /usr/lib64/
# COPY --from=ubi-dep /usr/lib64/libblkid.so.1 /usr/lib64/


# Copy the iofog-agent user and related configurations
COPY --from=ubi-dep /etc/passwd /etc/passwd
COPY --from=ubi-dep /etc/group /etc/group
COPY --from=ubi-dep /etc/shadow /etc/shadow


ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

COPY --from=builder packaging/iofog-agent/usr ./usr
COPY --from=builder packaging/iofog-agent/etc/systemd/system/iofog-agent.service /etc/systemd/system/iofog-agent.service
COPY --from=builder packaging/iofog-agent/etc/bash_completion.d /etc/bash_completion.d/
COPY --from=builder packaging/iofog-agent/etc/iofog-agent /etc/iofog-agent/

RUN true && \
    mv /etc/iofog-agent/config_new.xml /etc/iofog-agent/config.xml && \
    mv /etc/iofog-agent/config-development_new.xml /etc/iofog-agent/config-development.xml && \
    mv /etc/iofog-agent/config-production_new.xml /etc/iofog-agent/config-production.xml && \
    mv /etc/iofog-agent/config-switcher_new.xml /etc/iofog-agent/config-switcher.xml && \
    mv /etc/iofog-agent/cert_new.crt /etc/iofog-agent/cert.crt && \
    # </dev/urandom tr -dc A-Za-z0-9 | head -c32 > /etc/iofog-agent/local-api && \
    mkdir -p /var/backups/iofog-agent && \
    mkdir -p /var/log/iofog-agent && \
    mkdir -p /var/lib/iofog-agent && \
    mkdir -p /var/run/iofog-agent && \
    chown -R :iofog-agent /etc/iofog-agent && \
    chown -R :iofog-agent /var/log/iofog-agent && \
    chown -R :iofog-agent /var/lib/iofog-agent && \
    chown -R :iofog-agent /var/run/iofog-agent && \
    chown -R :iofog-agent /var/backups/iofog-agent && \
    chown -R :iofog-agent /usr/share/iofog-agent && \
    chmod 774 -R /etc/iofog-agent && \
    chmod 774 -R /var/log/iofog-agent && \
    chmod 774 -R /var/lib/iofog-agent && \
    chmod 774 -R /var/run/iofog-agent && \
    chmod 774 -R /var/backups/iofog-agent && \
    chmod 754 -R /usr/share/iofog-agent && \
    chmod 774 /etc/systemd/system/iofog-agent.service && \
    chmod 754 /usr/bin/iofog-agent && \
    chown :iofog-agent /usr/bin/iofog-agent && \
    true

COPY entrypoint.sh /etc/iofog-agent/entrypoint.sh
RUN chmod +x /etc/iofog-agent/entrypoint.sh
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ="Europe/Istanbul" \
    IOFOG_DAEMON=container

COPY LICENSE /licenses/LICENSE
LABEL org.opencontainers.image.description=agent
LABEL org.opencontainers.image.source=https://github.com/datasance/agent
LABEL org.opencontainers.image.licenses=EPL2.0

CMD ["/etc/iofog-agent/entrypoint.sh"]
