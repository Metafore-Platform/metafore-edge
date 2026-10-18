# Base images: public.ecr.aws, or this account's ECR pull-through cache of it (CI passes BASE_REGISTRY=<account>.dkr.ecr...
# /ecr-public). Pinned by digest, so both give the same bytes.
ARG BASE_REGISTRY=public.ecr.aws

# Stage 1: Build
FROM ${BASE_REGISTRY}/docker/library/eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
RUN apk add --no-cache maven
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -Dmaven.test.skip=true -B

# Stage 2: Runtime
FROM ${BASE_REGISTRY}/docker/library/eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
RUN apk add --no-cache procps
WORKDIR /app
COPY --from=build /build/target/metafore-edge-1.0.0-jar-with-dependencies.jar app.jar

# The commit this image was built from (CI passes it); the edge reports it as its version.
ARG EDGE_VERSION=1.0.0
ENV CONTROLLER_ID=edge-default \
    TENANT_ID=default-tenant \
    BROKER_URL=tcp://mqtt-broker:1883 \
    EDGE_VERSION=${EDGE_VERSION}

CMD ["java", "-jar", "app.jar"]
