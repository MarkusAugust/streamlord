import type { APIRoute } from "astro"
import { llmsTxt } from "../llms"

export const GET: APIRoute = async ({ site }) =>
  new Response(await llmsTxt(site ?? new URL("http://localhost")), {
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  })
