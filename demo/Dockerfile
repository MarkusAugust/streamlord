# streamlord-live, as a native image.
#
# No JRE and no Gradle: the binary is compiled before the image, in CI where the GraalVM
# toolchain and the memory for it live, and this file only packages it.
#
# distroless rather than alpine, because native-image links against glibc by default and
# alpine ships musl. The :nonroot tag runs as uid 65532 without a useradd step.
#
# distroless carries glibc and nothing else — no zlib, no libstdc++. The first deployment
# died on exactly that: the binary linked zlib dynamically, the image built and pushed and
# deployed without complaint, and the container exited with
#
#   /app/streamlord-live: error while loading shared libraries: libz.so.1
#
# The answer is not a fatter base image but a thinner binary: demo/build.gradle.kts passes
# --static-nolibc on Linux, which links zlib and the JDK's static libraries in and leaves
# only glibc to the container. CI checks the binary's dynamic dependencies against that
# promise, and starts the image and calls it, before anything is pushed.
#
# Locally, with a GraalVM 21 on JAVA_HOME:
#   ./gradlew :demo:nativeCompile
#   docker build -f demo/Dockerfile -t streamlord-live .
#
# The context is the repository root, because the artifact path starts at demo/.

FROM gcr.io/distroless/base-debian12:nonroot

WORKDIR /app
COPY --chown=nonroot:nonroot demo/build/native/nativeCompile/streamlord-live /app/streamlord-live

# Which commit is inside. /health returns it, so a caller can tell this deployment from the
# one it replaces — see Main.kt. Defaulted, so a local `docker build` needs no argument.
ARG BUILD_SHA=dev
ENV BUILD_SHA=$BUILD_SHA

# Railway sets PORT. Main.kt reads it and falls back to this one.
ENV PORT=8080
EXPOSE 8080

USER nonroot

ENTRYPOINT ["/app/streamlord-live"]
