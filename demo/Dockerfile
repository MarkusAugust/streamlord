# streamlord-live, as a native image.
#
# No JRE and no Gradle: the binary is compiled before the image, in CI where the GraalVM
# toolchain and the memory for it live, and this file only packages it.
#
# distroless rather than alpine, because native-image links against glibc by default and
# alpine ships musl. The :nonroot tag runs as uid 65532 without a useradd step.
#
# Locally, with a GraalVM 21 on JAVA_HOME:
#   ./gradlew :demo:nativeCompile
#   docker build -f demo/Dockerfile -t streamlord-live .
#
# The context is the repository root, because the artifact path starts at demo/.

FROM gcr.io/distroless/base-debian12:nonroot

WORKDIR /app
COPY --chown=nonroot:nonroot demo/build/native/nativeCompile/streamlord-live /app/streamlord-live

# Railway sets PORT. Main.kt reads it and falls back to this one.
ENV PORT=8080
EXPOSE 8080

USER nonroot

ENTRYPOINT ["/app/streamlord-live"]
