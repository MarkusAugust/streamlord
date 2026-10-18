import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.demo.FEED_START
import io.github.markusaugust.streamlord.demo.FeedSignals
import io.github.markusaugust.streamlord.demo.feedEvents

/*
 * The transcripts the front page shows. It is an Astro component and carries no `wire=` fence,
 * so the build names these in `sourced` and writes each to docs/src/generated/<name>.sse, which
 * the page imports as raw text.
 */

/** What streamlord-live's `/feed` sends from 13: the stream the test panel shows and asserts. */
fun feedFrom13(): List<DatastarEvent> = feedEvents(FeedSignals(heads = FEED_START))
