
      patchElements("""<div>no id</div>""")
      patchElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND)
      patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
      removeElements(" ")
      executeScript("var s = '</script>'")
      respondElements(elements { div { id = "x" } })
    
