# Imagem do servico Java no Railway (mapa da Etapa 9, §2.13). O Railway usa este Dockerfile sozinho; a config do
# servico que nao cabe aqui (healthcheck, draining, limite) fica no README, para aplicar no painel.
# tags exatas (Temurin 27+35) presas pelo digest usado no ensaio; para atualizar, trocar tag e digest juntos
FROM eclipse-temurin:27_35-jdk@sha256:473d02d477a8152a05dc42fde10b965754f1863d45e586a97763ef298965c48b AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# camada de dependencias: so refaz quando o pom muda
RUN ./mvnw -q -B dependency:go-offline
COPY src src
# -DskipTests pula surefire e failsafe (eles precisam de Docker/Testcontainers)
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:27_35-jre@sha256:0dd0465f90d398fa3226b56a3973f5d3c47ac323934dceebdab45ac8910bee6e
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
