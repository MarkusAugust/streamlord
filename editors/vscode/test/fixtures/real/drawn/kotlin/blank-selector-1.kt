
      s.patchElements("""<div>a</div>""")
      s.patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
      val c = '"'
      /* outer /* inner */ still comment: patchElements("<p>") */
      s.patchElements("""<div id="a"><span>x</div>""")
      s.removeElements(" ")
