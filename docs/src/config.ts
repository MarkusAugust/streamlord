/**
 * The address of streamlord-docs-service: the Ktor app that answers search
 * and drives the live demos. It lives here and nowhere else.
 */
export const SERVICE =
  import.meta.env.PUBLIC_SERVICE_URL ??
  (import.meta.env.DEV
    ? "http://localhost:8080"
    : "https://streamlord-live-production.up.railway.app")
