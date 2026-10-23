FROM quay.io/keycloak/keycloak:26.1

COPY target/keycloak-graph-email.jar /opt/keycloak/providers/

RUN /opt/keycloak/bin/kc.sh build
