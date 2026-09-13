FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libc6 libc-bin \
    && apt-get install -y --no-install-recommends ca-certificates curl python3 libvulkan1 libgomp1 libglvnd0 libglx0 libglx-mesa0 libx11-6 libx11-xcb1 libxext6 libxcb1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY lib ./lib
COPY src ./src
COPY web ./web
COPY hub.config.json ./hub.config.json
COPY docker/executables.json ./executables.json
COPY docker/start-hub.sh /usr/local/bin/start-hub.sh
COPY docker/start-breeze-instance.sh /usr/local/bin/start-breeze-instance.sh

RUN mkdir -p build/classes \
    && javac -encoding UTF-8 -d build/classes -cp 'lib/*' $(find src/main/java -name '*.java') \
    && cp -r src/main/resources/* build/classes/ \
    && chmod 0755 /usr/local/bin/start-hub.sh /usr/local/bin/start-breeze-instance.sh

ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx128m -XX:MaxDirectMemorySize=128m"

EXPOSE 18080

ENTRYPOINT ["/usr/local/bin/start-hub.sh"]
