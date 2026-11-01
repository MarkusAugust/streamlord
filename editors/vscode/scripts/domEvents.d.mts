/** Types of domEvents.mjs, for the test that holds catalog/dom-events.json to the pinned TypeScript. */
export interface LibDom {
  version: string;
  text: string;
}

export function pinnedLibDom(): LibDom;
export function domEvents(text: string): { name: string; mdn: string | null }[];
export function domEventsFile(libDom: LibDom): string;
