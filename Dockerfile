FROM centos:7
ARG JAVA_VERSION=1.8.0

RUN yum -y update \
    && yum -y install java-${JAVA_VERSION}-openjdk-headless openssl \
    && yum -y clean all

# Set JAVA_HOME env var
ENV JAVA_HOME /usr/lib/jvm/java

# Add strimzi user with UID 1001
# The user is in the group 0 to have access to the mounted volumes and storage
RUN useradd -r -m -u 1001 -g 0 strimzi

ARG strimzi_kafka_bridge_version=1.0-SNAPSHOT
ENV STRIMZI_KAFKA_BRIDGE_VERSION ${strimzi_kafka_bridge_version}
ENV STRIMZI_HOME=/opt/strimzi
RUN mkdir -p /opt/strimzi/bin && mkdir -p /opt/strimzi/lib
WORKDIR /opt/strimzi

COPY target/kafka-bridge-${strimzi_kafka_bridge_version}/kafka-bridge-${strimzi_kafka_bridge_version} ./

USER 1001

CMD ["/opt/strimzi/bin/run_bridge.sh"]