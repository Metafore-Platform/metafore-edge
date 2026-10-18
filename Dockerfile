# Stage 1: Build
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -Dmaven.test.skip=true -B

# Stage 2: Runtime
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre-alpine
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
