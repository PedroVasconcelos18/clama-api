# Imagem do servico Java no Railway (mapa da Etapa 9, §2.13). O Railway usa este Dockerfile sozinho; a config do
# servico que nao cabe aqui (healthcheck, draining, limite) fica no README, para aplicar no painel.
FROM eclipse-temurin:27-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# camada de dependencias: so refaz quando o pom muda
RUN ./mvnw -q -B dependency:go-offline
COPY src src
# -DskipTests pula surefire e failsafe (eles precisam de Docker/Testcontainers)
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:27-jre
WORKDIR /app
# media/ e o MEDIA_ROOT (clama.media-root: ./media); some no redeploy, como no Django (E3)
RUN useradd --system --home /app clama && mkdir -p /app/media && chown clama /app/media
COPY --from=build /src/target/clama-api.jar /app/clama-api.jar
USER clama
# HOBBY, limite de 1,5 GB (mapa §2.11). Uma variavel JAVA_TOOL_OPTIONS no Railway sobrescreve sem rebuild.
ENV JAVA_TOOL_OPTIONS="-Xmx640m -XX:MaxMetaspaceSize=256m -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# forma exec e sem argumento: o SIGTERM chega na JVM (PID 1) e nada vira modo comando. Start command do painel vazio.
CMD ["java", "-jar", "/app/clama-api.jar"]
