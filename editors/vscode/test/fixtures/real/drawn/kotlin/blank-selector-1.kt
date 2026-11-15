import io.github.markusaugust.streamlord.core.port.driving.DatastarStream

      sse.patchElements("""<div>a</div>""")
      sse.patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
      val c = '"'
      /* outer /* inner */ still comment: patchElements("<p>") */
      sse.patchElements("""<div id="a"><span>x</div>""")
      sse.removeElements(" ")
