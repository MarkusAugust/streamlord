# streamlord-live, the service behind the documentation site.
#
# No Gradle in here. The artifact is built before the image, in CI where the Gradle cache
# already lives, and this file only packages it — which keeps the image build to a few
# seconds and keeps a build failure in the place that has the logs for it.
#
# Locally:
#   ./gradlew :demo:installDist
#   docker build -f demo/Dockerfile -t streamlord-live .
#
# The context is the repository root, because the artifact path starts at demo/.

FROM eclipse-temurin:21-jre-alpine

# Not root. The service reads one file and answers HTTP; it has no business owning anything.
RUN addgroup -S live && adduser -S -G live live

WORKDIR /app
COPY --chown=live:live demo/build/install/demo/ /app/

# A documentation search over a hundred and fourteen sections held in memory. The cap is
# here so a leak announces itself as a restart rather than eating the whole container.
ENV JAVA_OPTS="-Xmx192m -XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

# Railway sets PORT. Main.kt reads it and falls back to this one.
ENV PORT=8080
EXPOSE 8080

USER live

# The start script the application plugin wrote. It honours JAVA_OPTS.
ENTRYPOINT ["/app/bin/demo"]
