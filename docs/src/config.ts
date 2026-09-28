/**
 * The address of streamlord-docs-service: the Ktor app that answers search
 * and drives the live demos. It lives here and nowhere else.
 */
export const SERVICE =
  import.meta.env.PUBLIC_SERVICE_URL ??
  "https://streamlord-docs-service.up.railway.app"
