import type { APIRoute } from "astro"
import { llmsFullTxt } from "../llms"

export const GET: APIRoute = async ({ site }) =>
  new Response(await llmsFullTxt(site ?? new URL("http://localhost")), {
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  })
